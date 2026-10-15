"""Explicit nonroot owner API composition, with no import-time resources.

ASGI entry: ``uvicorn app.ai.owner_api:create_app --factory``. The required
COPILOT_AI_OWNER_CONFIG points to a protected JSON descriptor (version 2):
owner_id, model, roots {identity, proofs, jobs, history}, receipt_key,
history_key, supervisor {socket, uid, gid}, attestation {application_id,
allowed_signer_sha256, trusted_root_sha256, revoked_certificate_sha256,
revocation_checked_ms, revocation_snapshot {path, sha256}}. The separate protected
snapshot is the pinned official serial-number status JSON. All fields are
required; no other settings are accepted. Version 1 is not deployment-ready.
The sole optional addition is vision {socket, uid, gid} for a separately admitted
image supervisor. It never enables a decoder or profile inside the HTTP process.

Provision four separate empty/private service-owned directories and two distinct
raw 32-byte key files before startup. Existing dedicated databases are supported;
running claims require trusted reconciliation, never automatic replay. Key
rotation, attestation snapshot renewal and backup expiry are operator work.
The model must match the supervisor descriptor; reasoning/tools/HTTP contracts
come unchanged from the existing policies. Run one ASGI worker with lifespan on.
"""
from __future__ import annotations

import asyncio
from contextlib import ExitStack, asynccontextmanager
from dataclasses import dataclass, replace
import fcntl
import hashlib
import json
import os
from pathlib import Path
import secrets
import stat
import time
from uuid import UUID

from sqlalchemy import select
from starlette.applications import Starlette

from ..ai_server import _error, create_bound_ai_app
from .activation import ActivationService
from .android_attestation import (AndroidAttestationVerifier, AttestationTrustPolicy,
                                 REVOCATION_SNAPSHOT_BYTES, decode_revoked_serials)
from .client_auth import AuthError, ClientAuth, credentials
from .contextual_chat import ContextualChatWorker, contextual_chat_policy
from .history_service import HistoryService
from .history_store import HistoryStore
from .job_executor import _complete_critical
from .job_ledger import EXECUTING, JobLedger, jobs as job_rows
from .job_policy import JobPolicy
from .job_service import AiJobService
from .native_text import TextSessionPolicy
from .meal_vision import meal_vision_policy
from .request_proof import RequestProofVerifier
from .text_supervisor import PeerIdentity, SupervisorTextRunner
from .vision_supervisor import SupervisorMealVisionWorker

CONFIG_LIMIT = 32_768
PURGE_SECONDS = 3600
FILE_FLAGS = os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK
DATABASES = {"identity": "identity.sqlite", "proofs": "proofs.sqlite", "jobs": "jobs.sqlite"}


class OwnerApiError(Exception):
    """Only fixed diagnostic codes, never paths, payloads or underlying errors."""


def _require_nonroot():
    if (os.getuid() == 0 or os.geteuid() == 0 or os.getgid() == 0 or os.getegid() == 0
            or os.getuid() != os.geteuid() or os.getgid() != os.getegid()):
        raise OwnerApiError("owner_api_nonroot_required")


def _path(value):
    if (type(value) is not str or not 1 < len(value) <= 4096 or not value.isascii()
            or any(ord(c) < 33 or ord(c) > 126 for c in value)
            or not value.startswith("/") or str(Path(value)) != value
            or any(part in (".", "..") for part in value.split("/"))):
        raise ValueError()
    return Path(value)


def _fields(value, fields):
    if type(value) is not dict or set(value) != set(fields.split()):
        raise ValueError()
    return value


def _unique(pairs):
    value = dict(pairs)
    if len(value) != len(pairs):
        raise ValueError()
    return value


def _directory(path):
    """Walk each component without following links, including ancestors."""
    return _walk_directory(path, Path("/"))


def _walk_directory(path, anchor):
    _path(str(path))
    fd = os.open(anchor, os.O_RDONLY | os.O_DIRECTORY | FILE_FLAGS)
    try:
        info = os.fstat(fd)
        if info.st_uid not in (0, os.geteuid()) or info.st_mode & 0o022:
            raise ValueError()
        for part in path.relative_to(anchor).parts:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | FILE_FLAGS, dir_fd=fd)
            os.close(fd)
            fd = child
            info = os.fstat(fd)
            if info.st_uid not in (0, os.geteuid()) or info.st_mode & 0o022:
                raise ValueError()
        result, fd = fd, None
        return result
    finally:
        if fd is not None:
            os.close(fd)


def _file(info, *, private_owner=False):
    if (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1
            or info.st_uid not in ((os.geteuid(),) if private_owner else (0, os.geteuid()))
            or stat.S_IMODE(info.st_mode) not in (0o400, 0o600)):
        raise ValueError()


def _identity(info):
    return info.st_dev, info.st_ino, info.st_uid, info.st_gid, info.st_mode, info.st_nlink


def _read_protected(path, maximum, *, minimum=1):
    with ExitStack() as stack:
        parent = _directory(path.parent)
        stack.callback(os.close, parent)
        fd = os.open(path.name, os.O_RDONLY | FILE_FLAGS, dir_fd=parent)
        stack.callback(os.close, fd)
        before = os.fstat(fd)
        _file(before)
        if not minimum <= before.st_size <= maximum:
            raise ValueError()
        raw = bytearray()
        while chunk := os.read(fd, min(4096, maximum + 1 - len(raw))):
            raw.extend(chunk)
            if len(raw) > maximum:
                raise ValueError()
        after = os.fstat(fd)
        current = os.stat(path.name, dir_fd=parent, follow_symlinks=False)
        def version(info):
            return _identity(info), info.st_size, info.st_mtime_ns, info.st_ctime_ns
        if (version(before) != version(after) or version(after) != version(current)
                or len(raw) != after.st_size):
            raise ValueError()
        fresh_parent = _directory(path.parent)
        stack.callback(os.close, fresh_parent)
        if _identity(os.fstat(parent))[:5] != _identity(os.fstat(fresh_parent))[:5]:
            raise ValueError()
        return bytes(raw), _identity(after)


@dataclass(frozen=True)
class OwnerConfiguration:
    owner_id: str
    policy: JobPolicy
    roots: dict[str, Path]
    receipt_key: Path
    history_key: Path
    socket_path: Path
    supervisor_peer: PeerIdentity
    attestation: AttestationTrustPolicy
    revocation_path: Path
    revocation_sha256: str
    vision_socket_path: Path | None = None
    vision_peer: PeerIdentity | None = None

    @classmethod
    def decode(cls, raw):
        if type(raw) is not bytes or not 2 <= len(raw) <= CONFIG_LIMIT:
            raise ValueError()
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique,
            parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
        _fields(value, "version owner_id model roots receipt_key history_key supervisor attestation"
                + (" vision" if "vision" in value else ""))
        if (type(value["version"]) is not int or value["version"] != 2
                or type(value["owner_id"]) is not str
                or str(UUID(value["owner_id"])) != value["owner_id"]):
            raise ValueError()
        roots = {name: _path(path) for name, path in
                 _fields(value["roots"], "identity proofs jobs history").items()}
        paths = list(roots.values())
        if any(a == b or a in b.parents or b in a.parents
               for index, a in enumerate(paths) for b in paths[index + 1:]):
            raise ValueError()
        receipt, history = _path(value["receipt_key"]), _path(value["history_key"])
        supervisor = _fields(value["supervisor"], "socket uid gid")
        socket_path = _path(supervisor["socket"])
        if len(str(socket_path).encode()) > 103 or receipt == history:
            raise ValueError()
        if any(path == root or root in path.parents for root in paths
               for path in (receipt, history, socket_path)):
            raise ValueError()
        attestation = _fields(value["attestation"], "application_id allowed_signer_sha256 "
            "trusted_root_sha256 revoked_certificate_sha256 revocation_checked_ms revocation_snapshot")
        revocation = _fields(attestation["revocation_snapshot"], "path sha256")
        revocation_path, revocation_digest = _path(revocation["path"]), revocation["sha256"]
        if (type(revocation_digest) is not str or len(revocation_digest) != 64
                or any(c not in "0123456789abcdef" for c in revocation_digest)
                or revocation_path in (receipt, history, socket_path)
                or any(revocation_path == root or root in revocation_path.parents for root in paths)):
            raise ValueError()
        for name in ("allowed_signer_sha256", "trusted_root_sha256", "revoked_certificate_sha256"):
            items = attestation[name]
            if (type(items) is not list or len(items) > 256
                    or any(type(item) is not str for item in items)
                    or len(set(items)) != len(items)):
                raise ValueError()
        trust = AttestationTrustPolicy(attestation["application_id"],
            frozenset(attestation["allowed_signer_sha256"]),
            frozenset(attestation["trusted_root_sha256"]),
            frozenset(attestation["revoked_certificate_sha256"]), attestation["revocation_checked_ms"])
        vision_path = vision_peer = None
        if "vision" in value:
            vision = _fields(value["vision"], "socket uid gid")
            vision_path, vision_peer = _path(vision["socket"]), PeerIdentity(vision["uid"], vision["gid"])
            if (len(str(vision_path).encode()) > 103 or vision_path == socket_path
                    or vision_path in (receipt, history, revocation_path)
                    or any(vision_path == root or root in vision_path.parents for root in paths)):
                raise ValueError()
        return cls(value["owner_id"], contextual_chat_policy(value["model"]), roots,
            receipt, history, socket_path, PeerIdentity(supervisor["uid"], supervisor["gid"]), trust,
            revocation_path, revocation_digest, vision_path, vision_peer)


def read_configuration(path: Path):
    try:
        path = _path(str(path))
        raw, _ = _read_protected(path, CONFIG_LIMIT, minimum=2)
        config = OwnerConfiguration.decode(raw)
        if any(path == root or root in path.parents for root in config.roots.values()):
            raise ValueError()
        if path == config.revocation_path:
            raise ValueError()
        snapshot, _ = _read_protected(config.revocation_path, REVOCATION_SNAPSHOT_BYTES, minimum=2)
        if hashlib.sha256(snapshot).hexdigest() != config.revocation_sha256:
            raise ValueError()
        return replace(config, attestation=replace(config.attestation,
            revoked_certificate_serials=decode_revoked_serials(snapshot)))
    except Exception:
        raise OwnerApiError("owner_api_configuration_rejected") from None


class _OwnerAuth(ClientAuth):
    def __init__(self, path, *, owner_id, storage_check):
        self.owner_id, self.storage_check = owner_id, storage_check
        super().__init__(path)

    def require_owner(self, owner):
        if owner != self.owner_id:
            raise AuthError()

    def _credential(self, db, digest, now):
        row = super()._credential(db, digest, now)
        self.require_owner(row["owner_id"])
        return row

    def authenticate_attested(self, *args, **kwargs):
        self.storage_check()
        device = super().authenticate_attested(*args, **kwargs)
        self.require_owner(device.owner_id)
        return self.authorize_attested_session(session_id=device.session_id,
            key_fingerprint=device.key_fingerprint, now_ms=kwargs["now_ms"])

    def authorize_attested_session(self, **kwargs):
        self.storage_check()
        device = super().authorize_attested_session(**kwargs)
        self.require_owner(device.owner_id)
        return device


class _OwnerActivation(ActivationService):
    def start(self, **kwargs):
        self.auth.storage_check()
        return super().start(**kwargs)

    def complete(self, **kwargs):
        self.auth.storage_check()
        return super().complete(**kwargs)

    def refresh(self, **kwargs):
        self.auth.storage_check()
        return super().refresh(**kwargs)

    def status(self, **kwargs):
        self.auth.storage_check()
        return super().status(**kwargs)

    def _request(self, db, identity, now):
        row = super()._request(db, identity, now)
        owner = db.scalar(select(credentials.c.owner_id).where(credentials.c.digest == row["code_digest"]))
        self.auth.require_owner(owner)
        return row

    def _device(self, db, session_id, now):
        row = super()._device(db, session_id, now)
        self.auth.require_owner(row["owner_id"])
        return row


class _OwnerHistory(HistoryStore):
    def __init__(self, *args, owner_id, **kwargs):
        self.owner_id = owner_id
        super().__init__(*args, **kwargs)

    def _identity(self, owner, device):
        if owner != self.owner_id:
            raise ValueError("history_owner_mismatch")
        return super()._identity(owner, device)


class _Root:
    def __init__(self, path, stack):
        self.path = path
        self.fd = _directory(path)
        stack.callback(os.close, self.fd)
        info = os.fstat(self.fd)
        if info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o700:
            raise ValueError()
        # Directory link counts change as SQLite files are created on macOS.
        self.identity = _identity(info)[:5]

    def check(self):
        fd = _directory(self.path)
        try:
            if _identity(os.fstat(fd))[:5] != self.identity:
                raise ValueError()
        finally:
            os.close(fd)

    def inventory(self, allowed):
        # Concurrent scans must not share a directory cursor or rewind each other.
        fd = _directory(self.path)
        try:
            if _identity(os.fstat(fd))[:5] != self.identity:
                raise ValueError()
            with os.scandir(fd) as entries:
                for index, entry in enumerate(entries):
                    if index >= len(allowed) or entry.name not in allowed:
                        raise ValueError()
                    try:
                        info = entry.stat(follow_symlinks=False)
                    except FileNotFoundError:
                        # SQLite deletes rollback journals after commit. Permanent
                        # database and lock identities must still remain present.
                        if entry.name.endswith("-journal"):
                            continue
                        raise
                    _file(info, private_owner=True)
        finally:
            os.close(fd)

    def database(self, name):
        try:
            fd = os.open(name, os.O_CREAT | os.O_EXCL | os.O_RDWR | FILE_FLAGS, 0o600, dir_fd=self.fd)
        except FileExistsError:
            fd = os.open(name, os.O_RDWR | FILE_FLAGS, dir_fd=self.fd)
        try:
            _file(os.fstat(fd), private_owner=True)
            if stat.S_IMODE(os.fstat(fd).st_mode) != 0o600:
                raise ValueError()
            return self.path / name
        finally:
            os.close(fd)


def _database(cls, path, stack, **kwargs):
    # Register disposal before __init__: SQLite/schema errors may occur after an
    # engine was allocated but before a constructor returned its instance.
    instance = cls.__new__(cls)
    def dispose():
        engine = getattr(instance, "engine", None)
        if engine is not None:
            engine.dispose()
    stack.callback(dispose)
    cls.__init__(instance, path, **kwargs)
    return instance


def _check_supervisor(config):
    endpoints = [(config.socket_path, config.supervisor_peer)]
    if config.vision_socket_path is not None:
        endpoints.append((config.vision_socket_path, config.vision_peer))
    for path, peer in endpoints:
        parent = _directory(path.parent)
        try:
            info = os.stat(path.name, dir_fd=parent, follow_symlinks=False)
            if (not stat.S_ISSOCK(info.st_mode) or info.st_uid != peer.uid
                    or info.st_gid != os.getegid() or stat.S_IMODE(info.st_mode) != 0o660
                    or info.st_nlink != 1):
                raise ValueError()
        finally:
            os.close(parent)


class _Resources:
    def __init__(self, config, clock_ms):
        self.stack = ExitStack()
        self.database_identities = {}
        try:
            now = clock_ms()
            if (type(now) is not int or not 0 <= now - config.attestation.revocation_checked_ms
                    < config.attestation.max_revocation_age_ms):
                raise ValueError()
            self.config = config
            self.roots = {name: _Root(path, self.stack) for name, path in config.roots.items()}
            if len({root.identity[:2] for root in self.roots.values()}) != 4:
                raise ValueError()
            root = self.roots["jobs"]
            fd = os.open("owner-api.lock", os.O_CREAT | os.O_RDWR | FILE_FLAGS, 0o600, dir_fd=root.fd)
            self.stack.callback(os.close, fd)
            _file(os.fstat(fd), private_owner=True)
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.lock_identity = _identity(os.fstat(fd))
            self.check()
            receipt, receipt_identity = _read_protected(config.receipt_key, 32, minimum=32)
            history, history_identity = _read_protected(config.history_key, 32, minimum=32)
            if receipt_identity[:2] == history_identity[:2] or secrets.compare_digest(receipt, history):
                raise ValueError()
            paths = {name: self.roots[name].database(filename) for name, filename in DATABASES.items()}
            self.database_identities = {name: _identity(path.lstat()) for name, path in paths.items()}
            auth = _database(_OwnerAuth, paths["identity"],
                             self.stack, owner_id=config.owner_id, storage_check=lambda: self.check())
            proofs = _database(RequestProofVerifier, paths["proofs"], self.stack)
            ledger = _database(JobLedger, paths["jobs"], self.stack)
            with ledger.engine.connect() as db:
                if db.scalar(select(job_rows.c.id).where(job_rows.c.state.in_(EXECUTING)).limit(1)):
                    raise OwnerApiError("owner_api_job_reconciliation_required")
                if db.scalar(select(job_rows.c.id).where(job_rows.c.owner_id != config.owner_id).limit(1)):
                    raise ValueError()
            store = _OwnerHistory(config.roots["history"], master_key=history, owner_id=config.owner_id)
            store.purge(now_ms=clock_ms())
            self.auth, self.proofs, self.ledger, self.store = auth, proofs, ledger, store
            self.activation = _OwnerActivation(auth, AndroidAttestationVerifier(config.attestation), proofs,
                                               receipt_key=receipt)
            policy = TextSessionPolicy(config.policy.model, config.policy.system_prompt)
            def factory(work):
                self.check()
                return ContextualChatWorker(work, store, clock_ms=clock_ms,
                    runner=SupervisorTextRunner(str(config.socket_path), config.supervisor_peer, policy))
            self.jobs = AiJobService(auth, proofs, ledger, factory, policy=config.policy,
                                      clock_ms=clock_ms, chat_archive=store)
            self.photo_jobs = None
            if config.vision_socket_path is not None:
                def photo_factory(work):
                    self.check()
                    return SupervisorMealVisionWorker(work, str(config.vision_socket_path),
                                                      config.vision_peer, clock_ms=clock_ms)
                self.photo_jobs = AiJobService(auth, proofs, ledger, photo_factory,
                    policy=meal_vision_policy(config.policy.model), clock_ms=clock_ms)
            self.services = tuple(item for item in (self.jobs, self.photo_jobs) if item is not None)
            self.bound = create_bound_ai_app(self.activation, jobs=self.jobs, meal_photo_jobs=self.photo_jobs,
                history=HistoryService(auth, proofs, store, owner_id=config.owner_id), clock_ms=clock_ms)
            self.check()
        except BaseException:
            self.stack.close()
            raise

    async def close_services(self):
        failed = False
        for service in reversed(self.services):
            try:
                await service.close()
            except (Exception, asyncio.CancelledError):
                failed = True
        if failed:
            raise OwnerApiError("owner_api_workers_cleanup_unconfirmed")

    def check(self):
        _check_supervisor(self.config)
        for name, root in self.roots.items():
            if name == "history":
                root.check()  # HistoryStore checks its bounded inventory under its own lock.
            else:
                filename = DATABASES[name]
                allowed = {filename, filename + "-journal"}
                if name == "jobs":
                    allowed.add("owner-api.lock")
                    if _identity((root.path / "owner-api.lock").lstat()) != self.lock_identity:
                        raise ValueError()
                root.inventory(allowed)
                if name in self.database_identities:
                    if _identity((root.path / filename).lstat()) != self.database_identities[name]:
                        raise ValueError()


class _OwnerApi:
    def __init__(self, path, clock_ms):
        self.path, self.clock_ms = path, clock_ms
        self.resources = None
        self._used = self._unavailable = False
        self._lifespan_app = Starlette(lifespan=self._lifespan)

    @asynccontextmanager
    async def _lifespan(self, app):
        resources = maintenance = None
        stop = asyncio.Event()
        try:
            _require_nonroot()
            if self._used:
                raise OwnerApiError("owner_api_lifetime_already_used")
            self._used = True
            config = read_configuration(self.path)
            resources, cancelled = await _complete_critical(asyncio.to_thread(_Resources, config, self.clock_ms))
            if cancelled:
                raise asyncio.CancelledError
            # Own start/close here so a failed/cancelled start also gets teardown.
            for service in resources.services:
                _, cancelled = await _complete_critical(service.start())
                if cancelled:
                    raise asyncio.CancelledError
            self.resources = resources
            maintenance = asyncio.create_task(self._maintain(resources, stop))
            yield
        except Exception:
            raise OwnerApiError("owner_api_start_or_stop_rejected") from None
        finally:
            self.resources = None
            stop.set()
            async def close():
                try:
                    if maintenance is not None:
                        await maintenance
                finally:
                    if resources is not None:
                        try:
                            await resources.close_services()
                        finally:
                            resources.stack.close()
            try:
                _, cancelled = await _complete_critical(close())
            except Exception:
                raise OwnerApiError("owner_api_cleanup_unconfirmed") from None
            if cancelled:
                raise asyncio.CancelledError

    async def _maintain(self, resources, stop):
        while True:
            try:
                await asyncio.wait_for(stop.wait(), timeout=PURGE_SECONDS)
                return
            except TimeoutError:
                try:
                    await asyncio.to_thread(resources.check)
                    await asyncio.to_thread(resources.store.purge, now_ms=self.clock_ms())
                except Exception:
                    self._unavailable = True
                    await resources.close_services()
                    return

    async def __call__(self, scope, receive, send):
        if scope["type"] == "lifespan":
            return await self._lifespan_app(scope, receive, send)
        resources = self.resources
        if resources is None or self._unavailable:
            return await _error(503, "service_unavailable")(scope, receive, send)
        # Keep the existing bounded admission ahead of filesystem/DB work.
        # Auth/activation adapters check pinned storage before their first SQL.
        return await resources.bound(scope, receive, send)


def create_owner_api(config_path: Path, *, clock_ms=None):
    """Explicit factory; mandatory ASGI lifespan owns all resources and cleanup."""
    _require_nonroot()
    try:
        path = _path(str(config_path))
        clock = clock_ms if clock_ms is not None else lambda: time.time_ns() // 1_000_000
        if not callable(clock):
            raise ValueError()
    except Exception:
        raise OwnerApiError("owner_api_configuration_rejected") from None
    return _OwnerApi(path, clock)


def create_app():
    """ASGI --factory entry point. No default descriptor or application mutation."""
    return create_owner_api(os.environ.get("COPILOT_AI_OWNER_CONFIG", ""))
