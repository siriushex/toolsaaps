"""Request signatures and durable replay rejection, NOT app attestation.

The public key must come from a trusted installation binding, never directly
from an unverified request header. HTTP/session/attestation wiring is separate.
IP is deliberately absent: mobile network changes do not change identity.
"""
from __future__ import annotations

import base64
import hashlib
import json
import re
from pathlib import Path
from uuid import UUID

from cryptography.exceptions import InvalidSignature, UnsupportedAlgorithm
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from sqlalchemy import Column, Index, Integer, MetaData, String, Table, create_engine, delete, func, inspect, select
from sqlalchemy.engine import URL
from sqlalchemy.exc import IntegrityError

from .client_auth import AuthError


metadata = MetaData()
receipts = Table("ai_request_proofs", metadata,
    Column("key_hash", String(64), primary_key=True),
    Column("nonce", String(36), primary_key=True),
    Column("expires_ms", Integer, nullable=False),
    Index("ai_request_proofs_expiry", "expires_ms"))


def signing_bytes(*, method: str, path: str, body: bytes, credential: str,
                  issued_ms: int, nonce: str) -> bytes:
    try:
        if (method not in ("GET", "POST", "DELETE") or not isinstance(path, str)
                or not re.fullmatch(r"/api/ai/v1/[a-zA-Z0-9/-]{1,180}", path)
                or type(body) is not bytes or len(body) > 8_388_608
                or not isinstance(credential, str) or not 1 <= len(credential) <= 256
                or type(issued_ms) is not int or not 60_000 < issued_ms < 2**63 - 60_000
                or not isinstance(nonce, str) or str(UUID(nonce)) != nonce):
            raise ValueError()
        return json.dumps({"v": 1, "aud": "https://diai.centv.ru", "method": method,
            "path": path, "body_sha256": hashlib.sha256(body).hexdigest(),
            "credential_sha256": hashlib.sha256(credential.encode("ascii")).hexdigest(),
            "issued_ms": issued_ms, "nonce": nonce},
            sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")
    except (ValueError, TypeError, UnicodeError):
        raise AuthError() from None


class RequestProofVerifier:
    def __init__(self, path: Path):
        self.engine = create_engine(URL.create("sqlite", database=str(path.resolve())),
                                    connect_args={"timeout": 10, "check_same_thread": False})
        if set(inspect(self.engine).get_table_names()) - set(metadata.tables):
            self.engine.dispose()
            raise ValueError("dedicated_database_required")
        metadata.create_all(self.engine)

    def verify(self, *, public_key_der: bytes, signature: str, now_ms: int, **request) -> str:
        message = signing_bytes(**request)
        issued = request["issued_ms"]
        try:
            if (type(now_ms) is not int or not issued - 60_000 <= now_ms < issued + 60_000
                    or type(public_key_der) is not bytes or not 1 <= len(public_key_der) <= 256
                    or not isinstance(signature, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", signature)):
                raise ValueError()
            raw = base64.b64decode(signature + "=" * (-len(signature) % 4), altchars=b"-_", validate=True)
            if base64.urlsafe_b64encode(raw).decode().rstrip("=") != signature:
                raise ValueError()
            key = serialization.load_der_public_key(public_key_der)
            if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
                raise ValueError()
            canonical_key = key.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
            if canonical_key != public_key_der:
                raise ValueError()
            key.verify(raw, message, ec.ECDSA(hashes.SHA256()))
        except (ValueError, TypeError, InvalidSignature, UnsupportedAlgorithm):
            raise AuthError() from None
        fingerprint = hashlib.sha256(canonical_key).hexdigest()
        with self.engine.connect() as db:
            db.exec_driver_sql("BEGIN IMMEDIATE")
            try:
                db.execute(delete(receipts).where(receipts.c.expires_ms <= now_ms))
                if db.scalar(select(func.count()).select_from(receipts)) >= 10_000:
                    raise AuthError()
                db.execute(receipts.insert().values(key_hash=fingerprint, nonce=request["nonce"],
                                                    expires_ms=issued + 60_000))
                db.commit()
            except IntegrityError:
                db.rollback()
                raise AuthError() from None
            except BaseException:
                db.rollback()
                raise
        return fingerprint
