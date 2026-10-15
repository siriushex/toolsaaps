"""Private per-device observational history. Keys and deployment remain external."""

from __future__ import annotations

import fcntl
import hashlib
import json
import os
import re
import secrets
import shutil
import sqlite3
import stat
import time
from contextlib import closing, contextmanager
from pathlib import Path

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from .history_contract import (
    HistoryError,
    MAX_RESPONSE_BYTES,
    RETENTION_MS,
    canonical_uuid,
    decode_batch,
    decode_query,
    encoded,
    valid_time,
)

FILE = re.compile(r"[0-9a-f]{64}\.sqlite(?:-journal)?\Z")
META_FIELDS = (
    "sequence",
    "record_id",
    "revision",
    "event_ms",
    "observed_ms",
    "kind",
    "source",
    "quality",
    "deleted",
    "expires_ms",
    "received_ms",
    "digest",
)
SCHEMA = (
    "CREATE TABLE history_manifest(id INTEGER PRIMARY KEY CHECK(id=1), owner TEXT NOT NULL, "
    "device TEXT NOT NULL, nonce BLOB NOT NULL, ciphertext BLOB NOT NULL)",
    "CREATE TABLE history_records(sequence INTEGER PRIMARY KEY AUTOINCREMENT, "
    "record_id TEXT NOT NULL,revision INTEGER NOT NULL,event_ms INTEGER NOT NULL,"
    "observed_ms INTEGER NOT NULL,kind TEXT NOT NULL,source TEXT NOT NULL,quality TEXT NOT NULL,"
    "deleted INTEGER NOT NULL,expires_ms INTEGER NOT NULL,received_ms INTEGER NOT NULL,"
    "digest TEXT NOT NULL,nonce BLOB NOT NULL,ciphertext BLOB NOT NULL,UNIQUE(record_id,revision))",
    "CREATE INDEX history_record_versions ON history_records(record_id,sequence)",
    "CREATE INDEX history_record_window ON history_records(event_ms,sequence)",
    "CREATE INDEX history_record_expiry ON history_records(expires_ms)",
    "CREATE TABLE history_receipts(batch_id TEXT PRIMARY KEY,digest TEXT NOT NULL,"
    "expires_ms INTEGER NOT NULL,nonce BLOB NOT NULL,ciphertext BLOB NOT NULL)",
    "CREATE INDEX history_receipt_expiry ON history_receipts(expires_ms)",
)


class HistoryStore:
    def __init__(
        self,
        root: Path,
        *,
        master_key: bytes,
        max_devices=8,
        max_rows=1_000_000,
        max_bytes=536_870_912,
        max_receipts=100_000,
        min_free_bytes=1_073_741_824,
    ):
        if type(master_key) is not bytes or len(master_key) != 32:
            raise ValueError("history_master_key_required")
        for value, low, high in (
            (max_devices, 1, 64),
            (max_rows, 1, 10_000_000),
            (max_bytes, 512, 2**31),
            (max_receipts, 1, 1_000_000),
            (min_free_bytes, 0, 2**50),
        ):
            if type(value) is not int or not low <= value <= high:
                raise ValueError("invalid_history_limit")
        root = Path(root)
        if root.is_symlink():
            raise ValueError("private_history_directory_required")
        root.mkdir(mode=0o700, exist_ok=True)
        self.root = root.resolve(strict=True)
        self._private(self.root, directory=True)
        self._master = master_key
        self.max_devices, self.max_rows, self.max_bytes = (
            max_devices,
            max_rows,
            max_bytes,
        )
        self.max_receipts, self.min_free_bytes = max_receipts, min_free_bytes

    @staticmethod
    def _private(path, *, directory=False):
        info = path.lstat()
        valid = (
            stat.S_ISDIR(info.st_mode)
            if directory
            else stat.S_ISREG(info.st_mode) and info.st_nlink == 1
        )
        if not valid or info.st_uid != os.geteuid() or info.st_mode & 0o077:
            raise ValueError("private_history_path_required")

    @staticmethod
    def _identity(owner, device):
        canonical_uuid(owner)
        if type(device) is not str or not re.fullmatch(r"[0-9a-f]{64}", device):
            raise ValueError("device_fingerprint_required")
        return hashlib.sha256((owner + ":" + device).encode("ascii")).hexdigest()

    def _cipher(self, owner, device):
        key = HKDF(
            algorithm=hashes.SHA256(),
            length=32,
            salt=b"history-store-v1",
            info=(owner + ":" + device).encode("ascii"),
        ).derive(self._master)
        return AESGCM(key)

    @staticmethod
    def _aad(owner, device, purpose, values):
        return encoded([1, owner, device, purpose, values])

    @contextmanager
    def _locked(self):
        fd = None
        try:
            self._private(self.root, directory=True)
            path = self.root / "history.lock"
            fd = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
            self._private(path)
            end = time.monotonic() + 5
            while True:
                try:
                    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except BlockingIOError:
                    if time.monotonic() >= end:
                        raise HistoryError(503, "history_storage_unavailable") from None
                    time.sleep(0.01)
            yield
        except HistoryError:
            raise
        except (
            OSError,
            ValueError,
            TypeError,
            sqlite3.Error,
            InvalidTag,
            UnicodeError,
            KeyError,
            IndexError,
        ):
            raise HistoryError(503, "history_storage_unavailable") from None
        finally:
            if fd is not None:
                os.close(fd)

    def _inventory(self):
        files = []
        with os.scandir(self.root) as entries:
            for index, entry in enumerate(entries):
                if index > self.max_devices * 2 + 2:
                    raise HistoryError(429, "history_capacity_reached")
                if entry.name == "history.lock":
                    continue
                if not FILE.fullmatch(entry.name):
                    raise ValueError("dedicated_history_directory_required")
                path = self.root / entry.name
                self._private(path)
                if path.suffix == ".sqlite":
                    files.append(path)
        if len(files) > self.max_devices:
            raise HistoryError(429, "history_capacity_reached")
        return files

    def _open(self, owner, device, *, create):
        path = self.root / (self._identity(owner, device) + ".sqlite")
        files = self._inventory()
        existed = path.exists() or path.is_symlink()
        if not existed and not create:
            return None
        if not existed:
            if len(files) >= self.max_devices:
                raise HistoryError(429, "history_capacity_reached")
            fd = os.open(
                path, os.O_CREAT | os.O_EXCL | os.O_RDWR | os.O_NOFOLLOW, 0o600
            )
            os.close(fd)
        self._private(path)
        db = None
        try:
            db = sqlite3.connect(path, timeout=5, isolation_level=None)
            db.row_factory = sqlite3.Row
            db.execute("PRAGMA trusted_schema=OFF")
            cipher = self._cipher(owner, device)
            aad = self._aad(owner, device, "manifest", [])
            if not existed:
                db.execute("BEGIN IMMEDIATE")
                for statement in SCHEMA:
                    db.execute(statement)
                nonce = secrets.token_bytes(12)
                db.execute(
                    "INSERT INTO history_manifest VALUES(1,?,?,?,?)",
                    (
                        owner,
                        device,
                        nonce,
                        cipher.encrypt(nonce, b"history-store-v1", aad),
                    ),
                )
                db.execute("PRAGMA user_version=1")
                db.commit()
            tables = {
                row[0]
                for row in db.execute(
                    "SELECT name FROM sqlite_master WHERE type='table'"
                )
            }
            if tables != {
                "history_manifest",
                "history_records",
                "history_receipts",
                "sqlite_sequence",
            }:
                raise ValueError("dedicated_history_database_required")
            if db.execute("PRAGMA user_version").fetchone()[0] != 1:
                raise ValueError("unsupported_history_schema")
            manifest = db.execute("SELECT * FROM history_manifest").fetchall()
            if (
                len(manifest) != 1
                or manifest[0]["owner"] != owner
                or manifest[0]["device"] != device
            ):
                raise ValueError("history_identity_mismatch")
            row = manifest[0]
            if (
                cipher.decrypt(row["nonce"], row["ciphertext"], aad)
                != b"history-store-v1"
            ):
                raise ValueError("history_key_mismatch")
            db.execute("PRAGMA secure_delete=ON")
            db.execute(
                "PRAGMA max_page_count="
                + str(max(256, (self.max_bytes * 4 + 4095) // 4096))
            )
            return db
        except BaseException:
            if db is not None:
                db.close()
            raise

    @staticmethod
    def _purge(db, now_ms):
        db.execute("DELETE FROM history_records WHERE expires_ms<=?", (now_ms,))
        db.execute("DELETE FROM history_receipts WHERE expires_ms<=?", (now_ms,))

    @staticmethod
    def _highwater(db):
        row = db.execute(
            "SELECT seq FROM sqlite_sequence WHERE name='history_records'"
        ).fetchone()
        return row[0] if row else 0

    def _check_quota(self, db):
        count, size = db.execute(
            "SELECT count(*),coalesce(sum(length(ciphertext)),0) FROM history_records"
        ).fetchone()
        receipts, extra = db.execute(
            "SELECT count(*),coalesce(sum(length(ciphertext)),0) FROM history_receipts"
        ).fetchone()
        if (
            count > self.max_rows
            or size + extra > self.max_bytes
            or receipts > self.max_receipts
        ):
            raise HistoryError(429, "history_capacity_reached")

    def _read_record(self, owner, device, cipher, row):
        plain = cipher.decrypt(
            row["nonce"],
            row["ciphertext"],
            self._aad(owner, device, "record", [row[key] for key in META_FIELDS]),
        )
        if hashlib.sha256(plain).hexdigest() != row["digest"]:
            raise ValueError("history_digest_mismatch")
        return json.loads(plain)

    def append(self, owner: str, device: str, body: bytes, *, now_ms: int):
        batch = decode_batch(body, now_ms=now_ms)
        digest = hashlib.sha256(body).hexdigest()
        with self._locked():
            self._identity(owner, device)
            if shutil.disk_usage(self.root).free < self.min_free_bytes:
                raise HistoryError(503, "history_storage_unavailable")
            db = self._open(owner, device, create=True)
            try:
                db.execute("BEGIN IMMEDIATE")
                self._purge(db, now_ms)
                cipher = self._cipher(owner, device)
                prior = db.execute(
                    "SELECT * FROM history_receipts WHERE batch_id=?", (batch.batchId,)
                ).fetchone()
                if prior:
                    if prior["digest"] != digest:
                        raise HistoryError(409, "history_conflict")
                    ack = json.loads(
                        cipher.decrypt(
                            prior["nonce"],
                            prior["ciphertext"],
                            self._aad(
                                owner,
                                device,
                                "receipt",
                                [batch.batchId, digest, prior["expires_ms"]],
                            ),
                        )
                    )
                    db.commit()
                    return ack
                changed = 0
                for record in batch.records:
                    plain = encoded(record.model_dump(exclude_unset=True))
                    record_digest = hashlib.sha256(plain).hexdigest()
                    old = db.execute(
                        "SELECT * FROM history_records WHERE record_id=? ORDER BY sequence DESC LIMIT 1",
                        (record.recordId,),
                    ).fetchone()
                    if old:
                        self._read_record(owner, device, cipher, old)
                        if (
                            record.revision == old["revision"]
                            and record_digest == old["digest"]
                        ):
                            continue
                        if (
                            record.revision <= old["revision"]
                            or record.eventAtMs != old["event_ms"]
                            or record.kind != old["kind"]
                            or record.source != old["source"]
                            or record.observedAtMs < old["observed_ms"]
                            or old["deleted"]
                            and not record.deleted
                        ):
                            raise HistoryError(409, "history_conflict")
                    if record.deleted:
                        db.execute(
                            "DELETE FROM history_records WHERE record_id=?",
                            (record.recordId,),
                        )
                    values = dict(
                        sequence=self._highwater(db) + 1,
                        record_id=record.recordId,
                        revision=record.revision,
                        event_ms=record.eventAtMs,
                        observed_ms=record.observedAtMs,
                        kind=record.kind,
                        source=record.source,
                        quality=record.quality,
                        deleted=int(record.deleted),
                        expires_ms=record.eventAtMs + RETENTION_MS,
                        received_ms=now_ms,
                        digest=record_digest,
                    )
                    nonce = secrets.token_bytes(12)
                    values.update(
                        nonce=nonce,
                        ciphertext=cipher.encrypt(
                            nonce,
                            plain,
                            self._aad(
                                owner,
                                device,
                                "record",
                                [values[key] for key in META_FIELDS],
                            ),
                        ),
                    )
                    db.execute(
                        "INSERT INTO history_records("
                        + ",".join(values)
                        + ") VALUES("
                        + ",".join("?" for _ in values)
                        + ")",
                        tuple(values.values()),
                    )
                    changed += 1
                ack = {
                    "batchId": batch.batchId,
                    "sequence": self._highwater(db),
                    "changedRecords": changed,
                }
                expires = now_ms + RETENTION_MS
                nonce = secrets.token_bytes(12)
                db.execute(
                    "INSERT INTO history_receipts VALUES(?,?,?,?,?)",
                    (
                        batch.batchId,
                        digest,
                        expires,
                        nonce,
                        cipher.encrypt(
                            nonce,
                            encoded(ack),
                            self._aad(
                                owner,
                                device,
                                "receipt",
                                [batch.batchId, digest, expires],
                            ),
                        ),
                    ),
                )
                self._check_quota(db)
                db.commit()
                return ack
            finally:
                db.close()

    def query(self, owner: str, device: str, body: bytes, *, now_ms: int):
        request = decode_query(body, now_ms=now_ms)
        with self._locked():
            db = self._open(owner, device, create=False)
            result = dict(
                snapshotSequence=0,
                nextAfterSequence=0,
                hasMore=False,
                provenance="DEVICE_DECLARED",
                records=[],
            )
            if db is None:
                if request.snapshotSequence not in (None, 0) or request.afterSequence:
                    raise HistoryError(400, "invalid_history_request")
                return result
            try:
                highwater = self._highwater(db)
                snapshot = (
                    highwater
                    if request.snapshotSequence is None
                    else request.snapshotSequence
                )
                if snapshot > highwater or request.afterSequence > snapshot:
                    raise HistoryError(400, "invalid_history_request")
                result.update(snapshotSequence=snapshot, nextAfterSequence=snapshot)
                # Bound SQL work as well as returned bytes; a partial scan is never success.
                remaining = 2000

                def budget():
                    nonlocal remaining
                    remaining -= 1
                    return int(remaining <= 0)

                db.set_progress_handler(budget, 1000)
                terms = ""
                args = [
                    request.afterSequence,
                    snapshot,
                    request.fromMs,
                    request.untilMs,
                    now_ms,
                    snapshot,
                ]
                if request.kinds:
                    terms = (
                        " AND r.kind IN (" + ",".join("?" for _ in request.kinds) + ")"
                    )
                    args.extend(request.kinds)
                args.append(request.limit + 1)
                rows = db.execute(
                    "SELECT r.* FROM history_records r WHERE r.sequence>? AND r.sequence<=? "
                    "AND r.event_ms>=? AND r.event_ms<? AND r.expires_ms>? AND NOT EXISTS "
                    "(SELECT 1 FROM history_records n WHERE n.record_id=r.record_id AND n.sequence>r.sequence "
                    "AND n.sequence<=?)" + terms + " ORDER BY r.sequence LIMIT ?",
                    args,
                ).fetchall()
                cipher = self._cipher(owner, device)
                for row in rows:
                    item = {
                        "sequence": row["sequence"],
                        "receivedAtMs": row["received_ms"],
                        "record": self._read_record(owner, device, cipher, row),
                    }
                    if (
                        len(result["records"]) >= request.limit
                        or len(encoded(result)) + len(encoded(item)) + 256
                        > MAX_RESPONSE_BYTES
                    ):
                        result["hasMore"] = True
                        result["nextAfterSequence"] = result["records"][-1]["sequence"]
                        break
                    result["records"].append(item)
                return result
            finally:
                db.close()

    def purge(self, *, now_ms: int):
        valid_time(now_ms)
        with self._locked():
            for path in self._inventory():
                with closing(sqlite3.connect(path)) as probe:
                    probe.row_factory = sqlite3.Row
                    row = probe.execute(
                        "SELECT owner,device FROM history_manifest WHERE id=1"
                    ).fetchone()
                    if (
                        row is None
                        or path.name
                        != self._identity(row["owner"], row["device"]) + ".sqlite"
                    ):
                        raise ValueError("history_identity_mismatch")
                    owner, device = row["owner"], row["device"]
                db = self._open(owner, device, create=False)
                try:
                    db.execute("BEGIN IMMEDIATE")
                    self._purge(db, now_ms)
                    db.commit()
                finally:
                    db.close()

    def context_snapshot(self, owner: str, device: str, *, now_ms: int):
        """Internal read-only context; a scan timeout never becomes partial success."""
        from .history_context import HistoryContext

        valid_time(now_ms)
        with self._locked():
            db = self._open(owner, device, create=False)
            context = HistoryContext(now_ms=now_ms, snapshot=0)
            if db is None:
                return context.finish()
            try:
                db.execute("BEGIN")
                context.snapshot = self._highwater(db)
                stop_at = time.monotonic() + 5
                instructions = 0

                def budget():
                    nonlocal instructions
                    instructions += 1000
                    return int(instructions > 50_000_000 or time.monotonic() >= stop_at)

                db.set_progress_handler(budget, 1000)
                rows = db.execute(
                    "SELECT r.* FROM history_records r WHERE r.event_ms>? AND r.event_ms<=? "
                    "AND r.observed_ms<=? AND r.received_ms<=? AND r.deleted=0 AND NOT EXISTS "
                    "(SELECT 1 FROM history_records n WHERE n.record_id=r.record_id "
                    "AND n.sequence>r.sequence) ORDER BY r.sequence",
                    (max(0, now_ms - RETENTION_MS), now_ms, now_ms, now_ms))
                cipher = self._cipher(owner, device)
                for count, row in enumerate(rows, 1):
                    if count > self.max_rows or time.monotonic() >= stop_at:
                        raise HistoryError(503, "history_context_unavailable")
                    context.add(dict(sequence=row["sequence"], receivedAtMs=row["received_ms"],
                        record=self._read_record(owner, device, cipher, row)))
                return context.finish()
            finally:
                db.close()
