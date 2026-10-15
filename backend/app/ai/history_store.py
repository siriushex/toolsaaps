"""Private per-device history and chat archive. Keys and deployment remain external."""

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
import struct
import time
from collections import OrderedDict
from contextlib import closing, contextmanager
from copy import deepcopy
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
# All record metadata are contract-bounded ASCII identifiers or integers. SQLite
# json_array emits the same compact array used in record AAD, without a Python
# JSON encoder and twelve named row lookups for every decrypted record.
CONTEXT_META = "json_array(" + ",".join("r." + key for key in META_FIELDS) + ")"
CONTEXT_ROWS = (
    # Context covers the retained year. A sequence scan avoids sorting every
    # ciphertext via the narrow-window index (including a large temp B-tree).
    " FROM history_records r NOT INDEXED WHERE r.event_ms>? AND r.event_ms<=? "
    "AND r.observed_ms<=? AND r.received_ms<=? AND r.deleted=0 AND NOT EXISTS "
    "(SELECT 1 FROM history_records n WHERE n.record_id=r.record_id "
    "AND n.sequence>r.sequence) ORDER BY r.sequence"
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
        # At most one bounded finished context per admitted device. Never durable
        # plaintext, and never trusted by timestamps/high-water alone.
        self._context_cache = OrderedDict()

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
            expected = {
                "history_manifest",
                "history_records",
                "history_receipts",
                "sqlite_sequence",
            }
            version = db.execute("PRAGMA user_version").fetchone()[0]
            if version in (2, 3):
                expected.add("server_chat")
            if version == 3:
                expected.add("daily_reports")
            if tables != expected:
                raise ValueError("dedicated_history_database_required")
            if version not in (1, 2, 3):
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
        version = db.execute("PRAGMA user_version").fetchone()[0]
        if version >= 2:
            db.execute("DELETE FROM server_chat WHERE expires_ms<=?", (now_ms,))
        if version >= 3:
            db.execute("DELETE FROM daily_reports WHERE expires_ms<=?", (now_ms,))

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
        version = db.execute("PRAGMA user_version").fetchone()[0]
        if version >= 2:
            chats, reserved = db.execute(
                "SELECT count(*),coalesce(sum(max(budget_bytes,length(ciphertext))),0) FROM server_chat"
            ).fetchone()
            count += chats
            extra += reserved
        if version >= 3:
            reports, report_bytes = db.execute(
                "SELECT count(*),coalesce(sum(length(ciphertext)),0) FROM daily_reports"
            ).fetchone()
            count += reports
            extra += report_bytes
        if (
            count > self.max_rows
            or size + extra > self.max_bytes
            or receipts > self.max_receipts
        ):
            raise HistoryError(429, "history_capacity_reached")

    def _read_plain(self, owner, device, cipher, row, *, aad=None):
        plain = cipher.decrypt(
            row["nonce"],
            row["ciphertext"],
            aad if aad is not None else self._aad(
                owner, device, "record", [row[key] for key in META_FIELDS]),
        )
        if hashlib.sha256(plain).hexdigest() != row["digest"]:
            raise ValueError("history_digest_mismatch")
        return plain

    def _read_record(self, owner, device, cipher, row):
        plain = self._read_plain(owner, device, cipher, row)
        return json.loads(plain)

    @staticmethod
    def _context_digest(digest, row):
        metadata = row["aad_values"].encode("utf-8")
        nonce, ciphertext = row["nonce"], row["ciphertext"]
        if type(nonce) is not bytes or len(nonce) != 12 or type(ciphertext) is not bytes:
            raise ValueError("invalid_history_ciphertext")
        # Length-framed canonical metadata and exact binary blobs, without
        # hex-encoding every ciphertext or keeping a second plaintext archive.
        digest.update(struct.pack(">II", len(metadata), len(ciphertext)))
        digest.update(metadata)
        digest.update(nonce)
        digest.update(ciphertext)

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
                self._context_cache.pop((owner, device), None)
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

    def daily_glucose_facts(self, owner: str, device: str, *, report_date: str,
                            zone_id: str, ranges, now_ms: int):
        """Internal bounded read only; no public route, cache or clinical writer."""
        from . import daily_glucose
        options = dict(report_date=report_date, zone_id=zone_id, ranges=ranges, now_ms=now_ms,
                       source_binding=self._identity(owner, device))
        empty = daily_glucose.build_daily_glucose([], snapshot_sequence=0, **options)
        window = empty.window
        if window.startMs <= now_ms - RETENTION_MS:
            raise ValueError("invalid_daily_glucose_input")
        with self._glucose_snapshot(owner, device,
                from_ms=max(1, window.startMs - daily_glucose.MAX_GAP_MS),
                until_ms=min(now_ms, window.endMs + daily_glucose.MAX_GAP_MS),
                now_ms=now_ms, row_limit=daily_glucose.MAX_INPUT_RECORDS) as (records, changes, snapshot):
            return daily_glucose.build_daily_glucose(records, snapshot_sequence=snapshot,
                sensor_changes=changes, **options)

    def daily_glucose_review(self, owner: str, device: str, *, report_date: str,
                            zone_id: str, ranges, now_ms: int):
        from . import daily_glucose, glucose_comparison

        options = dict(report_date=report_date, zone_id=zone_id, ranges=ranges, now_ms=now_ms,
                       source_binding=self._identity(owner, device))
        daily_glucose.build_daily_glucose([], snapshot_sequence=0, **options)
        windows = glucose_comparison.review_windows(report_date, zone_id, now_ms=now_ms)
        with self._glucose_snapshot(owner, device,
                from_ms=max(1, windows[0].startMs - daily_glucose.MAX_GAP_MS),
                until_ms=min(now_ms, windows[-1].endMs + daily_glucose.MAX_GAP_MS),
                now_ms=now_ms, row_limit=glucose_comparison.MAX_REVIEW_INPUT_RECORDS) as (records, changes, snapshot):
            return glucose_comparison.build_glucose_review(records, sensor_changes=changes,
                snapshot_sequence=snapshot, **options)

    def save_daily_glucose_report(self, owner, device, facts, *, now_ms):
        from .daily_reports import save_report

        return save_report(self, owner, device, facts, now_ms=now_ms)

    def get_daily_glucose_report(self, owner, device, **options):
        from .daily_reports import get_report

        return get_report(self, owner, device, **options)

    def list_daily_glucose_reports(self, owner, device, **options):
        from .daily_reports import list_reports

        return list_reports(self, owner, device, **options)

    def _enable_daily_reports(self, db):
        from .daily_reports import SCHEMA as REPORT_SCHEMA

        self._enable_chat_archive(db)
        if db.execute("PRAGMA user_version").fetchone()[0] == 2:
            db.execute("BEGIN IMMEDIATE")
            for statement in REPORT_SCHEMA:
                db.execute(statement)
            db.execute("PRAGMA user_version=3")
            db.commit()

    @contextmanager
    def _glucose_snapshot(self, owner, device, *, from_ms, until_ms, now_ms, row_limit):
        """One bounded read lifetime shared by daily and multi-day calculations."""
        from .history_contract import HistoryRecord

        with self._locked():
            db = self._open(owner, device, create=False)
            if db is None:
                yield [], [], 0
                return
            try:
                db.execute("BEGIN")
                snapshot = self._highwater(db)
                stop_at = time.monotonic() + 5
                instructions = 0

                def budget():
                    nonlocal instructions
                    instructions += 1000
                    return int(instructions > 50_000_000 or time.monotonic() >= stop_at)

                db.set_progress_handler(budget, 1000)
                limit = min(self.max_rows, row_limit)
                rows = db.execute(
                    "SELECT r.* FROM history_records r WHERE r.event_ms>=? AND r.event_ms<=? "
                    "AND r.observed_ms<=? AND r.received_ms<=? AND r.expires_ms>? "
                    "AND r.kind IN ('GLUCOSE','THERAPY') AND r.deleted=0 AND r.sequence<=? AND NOT EXISTS "
                    "(SELECT 1 FROM history_records n WHERE n.record_id=r.record_id "
                    "AND n.sequence>r.sequence AND n.sequence<=?) "
                    "ORDER BY r.event_ms,r.record_id LIMIT ?",
                    (from_ms, until_ms,
                     now_ms, now_ms, now_ms, snapshot, snapshot, limit + 1),
                )
                cipher, records, sensor_changes = self._cipher(owner, device), [], []
                for count, row in enumerate(rows, 1):
                    if count > limit or time.monotonic() >= stop_at:
                        raise HistoryError(503, "daily_glucose_unavailable")
                    record = HistoryRecord.model_validate(self._read_record(owner, device, cipher, row))
                    if record.kind == "GLUCOSE":
                        records.append(record)
                    elif record.data == {"eventType": "SENSOR_CHANGE"}:
                        sensor_changes.append(record)
                yield records, sensor_changes, snapshot
                if time.monotonic() >= stop_at:
                    raise HistoryError(503, "daily_glucose_unavailable")
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
                    self._context_cache.pop((owner, device), None)
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
                args = (max(0, now_ms - RETENTION_MS), now_ms, now_ms, now_ms)
                identity = (owner, device)
                cached = self._context_cache.get(identity)
                if cached is not None:
                    previous = cached[1]
                    first = previous["coverage"]["firstAtMs"]
                    if (previous["snapshotSequence"] != context.snapshot
                            or now_ms < previous["generatedAtMs"]
                            or first is not None and now_ms >= first + RETENTION_MS):
                        # Known revision/retention changes need one cold pass,
                        # rather than a fingerprint pass followed by a rebuild.
                        cached = None
                # Hash the complete eligible encrypted envelopes in this same
                # SQLite read snapshot. Includes metadata/AAD and exact blobs:
                # same sequence/count/mtime cannot conceal a corrupt ciphertext.
                # Selection is rerun at now_ms, so expiry, future cutoffs, latest
                # revisions and tombstones cannot use stale cached coverage.
                fingerprint = None
                if cached is not None:
                    digest = hashlib.sha256()
                    rows = db.execute("SELECT r.nonce,r.ciphertext," + CONTEXT_META
                                      + " AS aad_values" + CONTEXT_ROWS, args)
                    for count, row in enumerate(rows, 1):
                        if count > self.max_rows or time.monotonic() >= stop_at:
                            raise HistoryError(503, "history_context_unavailable")
                        self._context_digest(digest, row)
                    fingerprint = digest.digest()
                if cached is not None and cached[0] == fingerprint:
                    result = deepcopy(cached[1])
                    result.update(generatedAtMs=now_ms, snapshotSequence=context.snapshot,
                                  fromMs=max(1, now_ms - RETENTION_MS + 1), untilMs=now_ms + 1)
                    for item in result["recentRecords"]:
                        item["ageMs"] = now_ms - item["record"]["eventAtMs"]
                    if time.monotonic() >= stop_at:
                        raise HistoryError(503, "history_context_unavailable")
                    self._context_cache.move_to_end(identity)
                    return result
                self._context_cache.pop(identity, None)
                digest = hashlib.sha256()
                rows = db.execute("SELECT r.sequence,r.event_ms,r.received_ms,r.kind,r.quality,"
                                  "r.nonce,r.ciphertext,r.digest," + CONTEXT_META + " AS aad_values"
                                  + CONTEXT_ROWS, args)
                cipher = self._cipher(owner, device)
                prefix = encoded([1, owner, device, "record"])[:-1] + b","
                for count, row in enumerate(rows, 1):
                    if count > self.max_rows or time.monotonic() >= stop_at:
                        raise HistoryError(503, "history_context_unavailable")
                    plain = self._read_plain(owner, device, cipher, row,
                        aad=prefix + row["aad_values"].encode("utf-8") + b"]")
                    context.add_authenticated(row, plain)
                    if fingerprint is None:
                        self._context_digest(digest, row)
                result = context.finish()
                if time.monotonic() >= stop_at:
                    raise HistoryError(503, "history_context_unavailable")
                entry = (fingerprint if fingerprint is not None else digest.digest(), deepcopy(result))
                if time.monotonic() >= stop_at:
                    raise HistoryError(503, "history_context_unavailable")
                self._context_cache[identity] = entry
                self._context_cache.move_to_end(identity)
                if len(self._context_cache) > self.max_devices:
                    self._context_cache.popitem(last=False)
                return result
            finally:
                db.close()

    @staticmethod
    def _enable_chat_archive(db):
        from .server_chat import SCHEMA as CHAT_SCHEMA

        if db.execute("PRAGMA user_version").fetchone()[0] == 1:
            # Additive migration, separately committed before any archive data.
            # Reads and ordinary history ingestion never trigger this migration.
            db.execute("BEGIN IMMEDIATE")
            for statement in CHAT_SCHEMA:
                db.execute(statement)
            db.execute("PRAGMA user_version=2")
            db.commit()

    def accept_chat(self, job, request, policy, *, now_ms: int):
        """Trusted service-only write, before dispatch. Not a client history DTO."""
        from .server_chat import accepted_entry, decrypt_entry, encrypt_entry

        valid_time(now_ms)
        entry = accepted_entry(job, request, policy)
        if not job.created_ms <= now_ms < job.created_ms + RETENTION_MS:
            raise ValueError("chat_archive_clock_regression")
        owner, device = job.owner_id, job.key_hash
        with self._locked():
            if shutil.disk_usage(self.root).free < self.min_free_bytes:
                raise HistoryError(503, "history_storage_unavailable")
            db = self._open(owner, device, create=True)
            try:
                self._enable_chat_archive(db)
                db.execute("BEGIN IMMEDIATE")
                self._purge(db, now_ms)
                old = db.execute("SELECT * FROM server_chat WHERE job_id=?", (job.id,)).fetchone()
                if old is not None:
                    previous = decrypt_entry(self, owner, device, old)
                    mutable = {"state", "answer", "startedAtMs", "finishedAtMs"}
                    if any(previous[key] != value for key, value in entry.items() if key not in mutable):
                        raise HistoryError(409, "chat_archive_conflict")
                else:
                    values = encrypt_entry(self, owner, device, entry, updated_ms=now_ms, pending=True)
                    db.execute("INSERT INTO server_chat(" + ",".join(values) + ") VALUES("
                               + ",".join("?" for _ in values) + ")", tuple(values.values()))
                self._check_quota(db)
                db.commit()
            finally:
                db.close()

    def finish_chat(self, job, result, *, now_ms: int):
        """Record only the executor's settled outcome. Never reconstruct an answer."""
        from .server_chat import binding, decrypt_entry, encrypt_entry, final_entry

        valid_time(now_ms)
        binding(job)
        owner, device = job.owner_id, job.key_hash
        with self._locked():
            db = self._open(owner, device, create=False)
            if db is None:
                raise HistoryError(503, "chat_archive_missing")
            try:
                db.execute("BEGIN IMMEDIATE")
                old = db.execute("SELECT * FROM server_chat WHERE job_id=?", (job.id,)).fetchone()
                if old is None:
                    raise HistoryError(503, "chat_archive_missing")
                if now_ms < old["updated_ms"] or now_ms >= old["expires_ms"]:
                    raise ValueError("chat_archive_clock_regression")
                previous = decrypt_entry(self, owner, device, old)
                final = final_entry(previous, job, result)
                if now_ms < job.finished_ms:
                    raise ValueError("chat_archive_clock_regression")
                if previous["state"] != "OUTCOME_NOT_RECORDED":
                    if previous != final:
                        raise HistoryError(409, "chat_archive_conflict")
                else:
                    values = encrypt_entry(self, owner, device, final, updated_ms=now_ms, pending=False)
                    db.execute("UPDATE server_chat SET " + ",".join(key + "=?" for key in values)
                               + " WHERE job_id=?", (*values.values(), job.id))
                self._check_quota(db)
                db.commit()
            finally:
                db.close()

    def chat_context(self, owner: str, device: str, *, now_ms: int, exclude_job_id=None):
        """Bounded recent server exchanges; missing outcomes remain explicitly unknown."""
        from .server_chat import MAX_CONTEXT_BYTES, context_entry, decrypt_entry

        valid_time(now_ms)
        if exclude_job_id is not None:
            canonical_uuid(exclude_job_id)
        result = dict(origin="SERVER_ARCHIVE", generatedAtMs=now_ms, exchanges=[],
                      hasMore=False, outcomeMeaning="Recorded server outcome, not clinical validation")
        with self._locked():
            db = self._open(owner, device, create=False)
            if db is None:
                return result
            try:
                db.execute("BEGIN")
                if db.execute("PRAGMA user_version").fetchone()[0] == 1:
                    return result
                # Bounded SQL work, including scans over expired idle files.
                remaining = 2000

                def budget():
                    nonlocal remaining
                    remaining -= 1
                    return int(remaining <= 0)

                db.set_progress_handler(budget, 1000)
                rows = db.execute(
                    "SELECT * FROM server_chat WHERE created_ms<=? AND updated_ms<=? "
                    "AND expires_ms>? AND job_id!=? ORDER BY created_ms DESC,job_id DESC LIMIT 13",
                    (now_ms, now_ms, now_ms, exclude_job_id or "")).fetchall()
                for row in rows:
                    item = context_entry(decrypt_entry(self, owner, device, row))
                    if (len(result["exchanges"]) >= 12
                            or len(encoded(result)) + len(encoded(item)) + 64 > MAX_CONTEXT_BYTES):
                        result["hasMore"] = True
                        break
                    result["exchanges"].append(item)
                result["exchanges"].reverse()
                return result
            finally:
                db.close()

    def query_chats(self, owner: str, device: str, body: bytes, *, now_ms: int):
        """Full-text read pages. Membership is fixed; outcomes are current per page."""
        from .server_chat import decrypt_entry
        from .server_chat_query import (
            ArchiveCursor, MAX_RESPONSE_BYTES as CHAT_RESPONSE_BYTES, REVISION,
            decode_cursor, decode_query as decode_chat_query, encode_cursor, public_entry,
        )

        query = decode_chat_query(body, now_ms=now_ms)
        result = dict(revision=REVISION, origin="SERVER_ARCHIVE", generatedAtMs=now_ms,
                      snapshotScope="MEMBERSHIP_ONLY", records=[], hasMore=False, nextCursor=None)
        with self._locked():
            self._identity(owner, device)
            cursor = decode_cursor(self, owner, device, query, now_ms=now_ms)
            db = self._open(owner, device, create=False)
            if db is None:
                if cursor is not None:
                    raise HistoryError(409, "chat_cursor_stale")
                return result
            try:
                db.execute("BEGIN")
                if db.execute("PRAGMA user_version").fetchone()[0] == 1:
                    if cursor is not None:
                        raise HistoryError(409, "chat_cursor_stale")
                    return result
                stop_at = time.monotonic() + 5
                remaining = 2000

                def budget():
                    nonlocal remaining
                    remaining -= 1
                    return int(remaining <= 0 or time.monotonic() >= stop_at)

                db.set_progress_handler(budget, 1000)
                if cursor is None:
                    anchor = db.execute("SELECT rowid AS archive_row,* FROM server_chat ORDER BY rowid DESC LIMIT 1").fetchone()
                    if anchor is None:
                        return result
                    cursor = ArchiveCursor(upperRow=anchor["archive_row"], upperJob=anchor["job_id"],
                                           afterRow=0, issuedMs=now_ms)
                else:
                    anchor = db.execute("SELECT * FROM server_chat WHERE rowid=?", (cursor.upperRow,)).fetchone()
                    if anchor is None or anchor["job_id"] != cursor.upperJob:
                        # Without the original highest row, SQLite may reuse its
                        # rowid after purge. Refuse the cursor instead of mixing pages.
                        raise HistoryError(409, "chat_cursor_stale")
                decrypt_entry(self, owner, device, anchor)
                rows = db.execute(
                    "SELECT rowid AS archive_row,* FROM server_chat WHERE rowid>? AND rowid<=? "
                    "AND created_ms>=? AND created_ms<? AND updated_ms<=? AND expires_ms>? "
                    "ORDER BY rowid LIMIT ?",
                    (cursor.afterRow, cursor.upperRow, query.fromMs, query.untilMs,
                     now_ms, now_ms, query.limit + 1)).fetchall()
                last_row = cursor.afterRow
                for row in rows:
                    item = public_entry(decrypt_entry(self, owner, device, row), updated_ms=row["updated_ms"])
                    if (len(result["records"]) >= query.limit
                            or len(encoded(result)) + len(encoded(item)) + 2048 > CHAT_RESPONSE_BYTES):
                        if not result["records"]:
                            raise HistoryError(503, "chat_archive_unavailable")
                        result["hasMore"] = True
                        result["nextCursor"] = encode_cursor(self, owner, device, query,
                            cursor.model_copy(update=dict(afterRow=last_row)))
                        break
                    result["records"].append(item)
                    last_row = row["archive_row"]
                if time.monotonic() >= stop_at or len(encoded(result)) > CHAT_RESPONSE_BYTES:
                    raise HistoryError(503, "chat_archive_unavailable")
                return result
            finally:
                db.close()
