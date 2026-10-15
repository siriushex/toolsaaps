"""Private client identity store, separate from Codex login and therapy data.

Only trusted administration issues enrollment codes. HTTP admission, rate limits
and token delivery are separate; this module does not expose public endpoints.
Refresh reuse revokes the family, including a concurrently issued successor.
"""
from __future__ import annotations

import hashlib
import re
import secrets
from contextlib import contextmanager
from dataclasses import dataclass, field
from pathlib import Path
from uuid import UUID, uuid4

from sqlalchemy import (Column, Index, Integer, LargeBinary, MetaData, String, Table,
                        create_engine, inspect, select, update)
from sqlalchemy.engine import URL

from .android_attestation import AttestedKey, AndroidAttestationVerifier


class AuthError(Exception):
    def __init__(self):
        super().__init__("unauthorized")


metadata = MetaData()
sessions = Table(
    "ai_client_sessions", metadata,
    Column("id", String(36), primary_key=True),
    Column("owner_id", String(36), nullable=False),
    Column("expires_ms", Integer, nullable=False),
    Column("revoked_ms", Integer),
)
credentials = Table(
    "ai_client_credentials", metadata,
    Column("digest", String(64), primary_key=True),
    Column("kind", String(12), nullable=False),
    Column("owner_id", String(36), nullable=False),
    Column("session_id", String(36)),
    Column("issued_ms", Integer, nullable=False),
    Column("expires_ms", Integer, nullable=False),
    Column("consumed_ms", Integer),
    Index("ai_client_credentials_session", "session_id"),
)
attested_device_keys = Table(
    "ai_attested_device_keys", metadata,
    Column("key_hash", String(64), primary_key=True),
    Column("owner_id", String(36), nullable=False),
    Column("session_id", String(36), nullable=False),
    Column("public_key_der", LargeBinary, nullable=False),
    Column("certificate_chain_hash", String(64), nullable=False),
    Column("application_id", String(128), nullable=False),
    Column("enrolled_ms", Integer, nullable=False),
    Column("revoked_ms", Integer),
    Index("ai_attested_device_owner_session", "owner_id", "session_id"),
)
subscription_grants = Table(
    "ai_subscription_grants", metadata,
    Column("code_digest", String(64), primary_key=True),
    Column("duration_days", Integer, nullable=False),
    Column("session_id", String(36), unique=True),
    Column("activated_ms", Integer),
    Column("expires_ms", Integer),
)
activation_requests = Table(
    "ai_activation_requests", metadata,
    Column("id", String(36), primary_key=True),
    Column("code_digest", String(64), nullable=False, unique=True),
    Column("challenge", LargeBinary, nullable=False),
    Column("created_ms", Integer, nullable=False),
    Column("expires_ms", Integer, nullable=False),
    Column("retain_until_ms", Integer, nullable=False),
    Column("session_id", String(36)),
    Column("response_access_digest", String(64)),
)
refresh_requests = Table(
    "ai_refresh_requests", metadata,
    Column("id", String(36), primary_key=True),
    Column("credential_digest", String(64), nullable=False, unique=True),
    Column("session_id", String(36), nullable=False),
    Column("created_ms", Integer, nullable=False),
    Column("response_access_digest", String(64), nullable=False),
)
response_receipts = Table(
    "ai_response_receipts", metadata,
    Column("id", String(48), primary_key=True),
    Column("operation", String(12), nullable=False),
    Column("request_id", String(36), nullable=False),
    Column("session_id", String(36), nullable=False),
    Column("created_ms", Integer, nullable=False),
    Column("retain_until_ms", Integer, nullable=False),
    Column("nonce", LargeBinary, nullable=False),
    Column("ciphertext", LargeBinary, nullable=False),
    Index("ai_response_receipts_retention", "retain_until_ms"),
)
ENROLLMENT_MS = 600_000
ACCESS_MS = 600_000
SESSION_MS = 30 * 86_400_000
SUBSCRIPTION_CODE_MS = 86_400_000
MAX_SUBSCRIPTION_DAYS = 366
_CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


@dataclass(frozen=True)
class Tokens:
    access_token: str = field(repr=False)
    refresh_token: str = field(repr=False)
    access_expires_ms: int
    refresh_expires_ms: int
    subscription_expires_ms: int | None = None


@dataclass(frozen=True)
class AttestedDevice:
    owner_id: str
    session_id: str
    key_fingerprint: str
    public_key_der: bytes = field(repr=False)
    subscription_expires_ms: int | None = None


def _time(value):
    if type(value) is not int or not 0 < value < 2**63 - MAX_SUBSCRIPTION_DAYS * 86_400_000:
        raise ValueError("invalid_timestamp")


def _subscription_digest(code):
    if (not isinstance(code, str) or not 16 <= len(code) <= 64
            or not code.isascii() or not re.fullmatch(r"[A-Za-z0-9 -]+", code)):
        raise AuthError()
    canonical = code.upper().replace("-", "").replace(" ", "")
    if len(canonical) != 16 or any(c not in _CODE_ALPHABET for c in canonical):
        raise AuthError()
    return hashlib.sha256(("subscription:" + canonical).encode("ascii")).hexdigest()


def _digest(token, kind):
    if kind == "enroll" and isinstance(token, str) and not token.startswith("enroll."):
        return _subscription_digest(token)
    if not isinstance(token, str) or not re.fullmatch(kind + r"\.[A-Za-z0-9_-]{43}", token):
        raise AuthError()
    return hashlib.sha256(token.encode("ascii")).hexdigest()


class ClientAuth:
    def __init__(self, path: Path):
        self.engine = create_engine(URL.create("sqlite", database=str(path.resolve())),
                                    connect_args={"timeout": 10, "check_same_thread": False})
        if set(inspect(self.engine).get_table_names()) - set(metadata.tables):
            self.engine.dispose()
            raise ValueError("dedicated_database_required")
        metadata.create_all(self.engine)

    @contextmanager
    def _write(self):
        with self.engine.connect() as db:
            db.exec_driver_sql("BEGIN IMMEDIATE")
            try:
                yield db
                db.commit()
            except BaseException:
                db.rollback()
                raise

    @staticmethod
    def _mint(db, kind, owner, session_id, now, expires):
        token = kind + "." + secrets.token_urlsafe(32)
        db.execute(credentials.insert().values(
            digest=_digest(token, kind), kind=kind, owner_id=owner,
            session_id=session_id, issued_ms=now, expires_ms=expires,
        ))
        return token

    @classmethod
    def _tokens(cls, db, session, now):
        expires = min(now + ACCESS_MS, session["expires_ms"])
        subscription = db.execute(select(subscription_grants.c.expires_ms).where(
            subscription_grants.c.session_id == session["id"])).first()
        args = (session["owner_id"], session["id"], now)
        return Tokens(cls._mint(db, "access", *args, expires),
                      cls._mint(db, "refresh", *args, min(now + SESSION_MS, session["expires_ms"])),
                      expires, min(now + SESSION_MS, session["expires_ms"]),
                      subscription.expires_ms if subscription else None)

    @staticmethod
    def _credential(db, digest, now):
        row = db.execute(select(credentials).where(credentials.c.digest == digest)).mappings().first()
        if row is None or not row["issued_ms"] <= now < row["expires_ms"]:
            raise AuthError()
        return row

    @staticmethod
    def _session(db, row, now):
        session = db.execute(select(sessions).where(sessions.c.id == row["session_id"])).mappings().first()
        if session is None or session["revoked_ms"] is not None or now >= session["expires_ms"]:
            raise AuthError()
        return session

    def issue_enrollment(self, *, owner_id: str, now_ms: int) -> str:
        """Trusted admin only: owner is never taken from an enrollment request."""
        _time(now_ms)
        if not isinstance(owner_id, str) or str(UUID(owner_id)) != owner_id:
            raise ValueError("canonical_uuid_required")
        with self._write() as db:
            return self._mint(db, "enroll", owner_id, None, now_ms, now_ms + ENROLLMENT_MS)

    def issue_subscription(self, *, owner_id: str, duration_days: int, now_ms: int) -> str:
        """Trusted admin: one 80-bit code, one device, duration begins at activation."""
        _time(now_ms)
        if not isinstance(owner_id, str) or str(UUID(owner_id)) != owner_id:
            raise ValueError("canonical_uuid_required")
        if type(duration_days) is not int or not 1 <= duration_days <= MAX_SUBSCRIPTION_DAYS:
            raise ValueError("invalid_subscription_duration")
        raw = "".join(secrets.choice(_CODE_ALPHABET) for _ in range(16))
        code = "-".join(raw[i:i + 4] for i in range(0, 16, 4))
        digest = _subscription_digest(code)
        with self._write() as db:
            db.execute(credentials.insert().values(
                digest=digest, kind="enroll", owner_id=owner_id, session_id=None,
                issued_ms=now_ms, expires_ms=now_ms + SUBSCRIPTION_CODE_MS,
            ))
            db.execute(subscription_grants.insert().values(code_digest=digest,
                                                           duration_days=duration_days))
        return code

    def enroll(self, code: str, *, now_ms: int) -> Tokens:
        _time(now_ms)
        # Short subscription codes must never reach the unbound local HTTP pilot.
        if not isinstance(code, str) or not code.startswith("enroll."):
            raise AuthError()
        digest = _digest(code, "enroll")
        with self._write() as db:
            row = self._credential(db, digest, now_ms)
            if row["consumed_ms"] is not None:
                raise AuthError()
            db.execute(update(credentials).where(credentials.c.digest == digest).values(consumed_ms=now_ms))
            session = dict(id=str(uuid4()), owner_id=row["owner_id"], expires_ms=now_ms + SESSION_MS)
            db.execute(sessions.insert().values(**session))
            return self._tokens(db, session, now_ms)

    def enroll_attested(self, code: str, *, certificate_chain_der, expected_challenge: bytes,
                        verifier, now_ms: int) -> Tokens:
        """Consume an admin code only after an attested P-256 identity is verified."""
        _time(now_ms)
        digest = _digest(code, "enroll")
        # Cheap admission first; repeat it under the write lock after verification.
        with self.engine.connect() as db:
            row = self._credential(db, digest, now_ms)
            if row["kind"] != "enroll" or row["consumed_ms"] is not None:
                raise AuthError()
        try:
            if not isinstance(verifier, AndroidAttestationVerifier):
                raise ValueError()
            attested = verifier.verify(certificate_chain_der=certificate_chain_der,
                                       expected_challenge=expected_challenge, now_ms=now_ms)
            if (not isinstance(attested, AttestedKey)
                    or not re.fullmatch(r"[0-9a-f]{64}", attested.key_fingerprint)):
                raise ValueError()
        except Exception:
            raise AuthError() from None
        with self._write() as db:
            session = self._enroll_verified(db, digest, attested, now_ms,
                                            require_subscription=not code.startswith("enroll."))
            return self._tokens(db, session, now_ms)

    def _enroll_verified(self, db, digest, attested, now_ms, *, require_subscription):
        if db.execute(select(attested_device_keys.c.key_hash).where(
                attested_device_keys.c.key_hash == attested.key_fingerprint)).first() is not None:
            raise AuthError()
        row = self._credential(db, digest, now_ms)
        if row["kind"] != "enroll" or row["consumed_ms"] is not None:
            raise AuthError()
        grant = db.execute(select(subscription_grants).where(
            subscription_grants.c.code_digest == digest)).mappings().first()
        if require_subscription and grant is None:
            raise AuthError()
        if grant is not None and grant["session_id"] is not None:
            raise AuthError()
        db.execute(update(credentials).where(credentials.c.digest == digest).values(consumed_ms=now_ms))
        duration_ms = grant["duration_days"] * 86_400_000 if grant else SESSION_MS
        session = dict(id=str(uuid4()), owner_id=row["owner_id"], expires_ms=now_ms + duration_ms)
        db.execute(sessions.insert().values(**session))
        if grant is not None:
            db.execute(update(subscription_grants).where(
                subscription_grants.c.code_digest == digest).values(
                    session_id=session["id"], activated_ms=now_ms, expires_ms=session["expires_ms"]))
        db.execute(attested_device_keys.insert().values(
            key_hash=attested.key_fingerprint, owner_id=row["owner_id"], session_id=session["id"],
            public_key_der=attested.public_key_der,
            certificate_chain_hash=attested.certificate_chain_sha256,
            application_id=attested.application_id, enrolled_ms=now_ms,
        ))
        return session

    def authenticate(self, access_token: str, *, now_ms: int) -> str:
        _time(now_ms)
        digest = _digest(access_token, "access")
        with self.engine.connect() as db:
            # One query binds credential and family revocation to the same read snapshot.
            row = db.execute(select(credentials.c.owner_id).join(
                sessions, sessions.c.id == credentials.c.session_id).where(
                    credentials.c.digest == digest, credentials.c.consumed_ms.is_(None),
                    credentials.c.issued_ms <= now_ms, credentials.c.expires_ms > now_ms,
                    sessions.c.revoked_ms.is_(None), sessions.c.expires_ms > now_ms,
                )).first()
            if row is None:
                raise AuthError()
            return row.owner_id

    def authenticate_attested(self, access_token: str, *, key_fingerprint: str,
                              now_ms: int) -> AttestedDevice:
        _time(now_ms)
        digest = _digest(access_token, "access")
        if not isinstance(key_fingerprint, str) or not re.fullmatch(r"[0-9a-f]{64}", key_fingerprint):
            raise AuthError()
        with self.engine.connect() as db:
            row = db.execute(select(
                attested_device_keys.c.owner_id, attested_device_keys.c.session_id,
                attested_device_keys.c.key_hash,
                attested_device_keys.c.public_key_der, subscription_grants.c.expires_ms,
            ).join(credentials, credentials.c.session_id == attested_device_keys.c.session_id).join(
                sessions, sessions.c.id == credentials.c.session_id).outerjoin(
                subscription_grants, subscription_grants.c.session_id == sessions.c.id).where(
                    credentials.c.digest == digest, credentials.c.consumed_ms.is_(None),
                    credentials.c.issued_ms <= now_ms, credentials.c.expires_ms > now_ms,
                    sessions.c.revoked_ms.is_(None), sessions.c.expires_ms > now_ms,
                    attested_device_keys.c.owner_id == credentials.c.owner_id,
                    attested_device_keys.c.key_hash == key_fingerprint,
                    attested_device_keys.c.revoked_ms.is_(None),
                )).mappings().first()
            if row is None:
                raise AuthError()
            return AttestedDevice(owner_id=row["owner_id"], session_id=row["session_id"],
                                  key_fingerprint=row["key_hash"],
                                  public_key_der=bytes(row["public_key_der"]),
                                  subscription_expires_ms=row["expires_ms"])

    def authorize_attested_session(self, *, session_id: str, key_fingerprint: str,
                                   now_ms: int) -> AttestedDevice:
        """Recheck the stable grant/key binding without relying on an access token."""
        _time(now_ms)
        try:
            if (not isinstance(session_id, str) or str(UUID(session_id)) != session_id
                    or not isinstance(key_fingerprint, str)
                    or not re.fullmatch(r"[0-9a-f]{64}", key_fingerprint)):
                raise ValueError()
        except ValueError:
            raise AuthError() from None
        with self.engine.connect() as db:
            row = db.execute(select(
                attested_device_keys.c.owner_id, attested_device_keys.c.session_id,
                attested_device_keys.c.key_hash, attested_device_keys.c.public_key_der,
                subscription_grants.c.expires_ms,
            ).join(sessions, sessions.c.id == attested_device_keys.c.session_id).join(
                subscription_grants,
                subscription_grants.c.session_id == sessions.c.id,
            ).where(
                sessions.c.id == session_id,
                sessions.c.revoked_ms.is_(None), sessions.c.expires_ms > now_ms,
                attested_device_keys.c.key_hash == key_fingerprint,
                attested_device_keys.c.revoked_ms.is_(None),
                subscription_grants.c.activated_ms.is_not(None),
                subscription_grants.c.activated_ms <= now_ms,
                subscription_grants.c.expires_ms > now_ms,
            )).mappings().first()
            if row is None:
                raise AuthError()
            return AttestedDevice(owner_id=row["owner_id"], session_id=row["session_id"],
                                  key_fingerprint=row["key_hash"],
                                  public_key_der=bytes(row["public_key_der"]),
                                  subscription_expires_ms=row["expires_ms"])

    def refresh(self, refresh_token: str, *, now_ms: int) -> Tokens:
        _time(now_ms)
        digest = _digest(refresh_token, "refresh")
        result = None
        with self._write() as db:
            row = self._credential(db, digest, now_ms)
            session = self._session(db, row, now_ms)
            if row["consumed_ms"] is not None:
                db.execute(update(sessions).where(sessions.c.id == session["id"]).values(revoked_ms=now_ms))
            else:
                db.execute(update(credentials).where(
                    credentials.c.session_id == session["id"], credentials.c.consumed_ms.is_(None)
                ).values(consumed_ms=now_ms))
                result = self._tokens(db, session, now_ms)
        # Raise only after COMMIT, otherwise reuse detection would undo revocation.
        if result is None:
            raise AuthError()
        return result

    def revoke(self, refresh_token: str, *, now_ms: int) -> None:
        _time(now_ms)
        digest = _digest(refresh_token, "refresh")
        with self._write() as db:
            row = self._credential(db, digest, now_ms)
            db.execute(update(sessions).where(sessions.c.id == row["session_id"],
                                             sessions.c.revoked_ms.is_(None)).values(revoked_ms=now_ms))

    def revoke_owner(self, *, owner_id: str, now_ms: int) -> None:
        """Trusted admin only; revoke existing access, not future admin grants."""
        _time(now_ms)
        if not isinstance(owner_id, str) or str(UUID(owner_id)) != owner_id:
            raise ValueError("canonical_uuid_required")
        with self._write() as db:
            db.execute(update(sessions).where(sessions.c.owner_id == owner_id,
                                             sessions.c.revoked_ms.is_(None)).values(revoked_ms=now_ms))
            db.execute(update(credentials).where(credentials.c.owner_id == owner_id,
                                                credentials.c.kind == "enroll",
                                                credentials.c.consumed_ms.is_(None)).values(consumed_ms=now_ms))
            db.execute(update(attested_device_keys).where(
                attested_device_keys.c.owner_id == owner_id,
                attested_device_keys.c.revoked_ms.is_(None)).values(revoked_ms=now_ms))

    def revoke_attested_device(self, *, owner_id: str, key_fingerprint: str, now_ms: int) -> None:
        """Trusted admin only. Device revocation also ends its token family."""
        _time(now_ms)
        if not isinstance(owner_id, str) or str(UUID(owner_id)) != owner_id:
            raise ValueError("canonical_uuid_required")
        if not isinstance(key_fingerprint, str) or not re.fullmatch(r"[0-9a-f]{64}", key_fingerprint):
            raise ValueError("invalid_key_fingerprint")
        with self._write() as db:
            device = db.execute(select(attested_device_keys).where(
                attested_device_keys.c.owner_id == owner_id,
                attested_device_keys.c.key_hash == key_fingerprint)).mappings().first()
            if device is None:
                raise ValueError("unknown_attested_device")
            db.execute(update(attested_device_keys).where(
                attested_device_keys.c.key_hash == key_fingerprint,
                attested_device_keys.c.revoked_ms.is_(None)).values(revoked_ms=now_ms))
            db.execute(update(sessions).where(sessions.c.id == device["session_id"],
                                             sessions.c.revoked_ms.is_(None)).values(revoked_ms=now_ms))
            db.execute(update(credentials).where(credentials.c.owner_id == owner_id,
                                                credentials.c.kind == "enroll",
                                                credentials.c.consumed_ms.is_(None)).values(consumed_ms=now_ms))
