"""App-bound code activation. No model execution or therapy dependencies."""
from __future__ import annotations

import base64
import json
import secrets
from dataclasses import dataclass
from uuid import UUID

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from sqlalchemy import delete, func, select, update

from .android_attestation import AttestationError
from .client_auth import (AuthError, activation_requests, attested_device_keys,
                          credentials, refresh_requests, response_receipts, sessions,
                          subscription_grants, Tokens, _digest, _subscription_digest, _time)


BASE = "/api/ai/v1"
CHALLENGE_MS = 600_000
RECOVERY_MS = 86_400_000
MAX_RECEIPTS = 1000


@dataclass(frozen=True)
class ActivationChallenge:
    request_id: str
    challenge: str
    expires_ms: int
    server_time_ms: int


def _uuid(value):
    try:
        if not isinstance(value, str) or str(UUID(value)) != value:
            raise ValueError()
    except ValueError:
        raise AuthError() from None
    return value


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError()
        result[key] = value
    return result


def decode_body(body, fields, maximum=96_000):
    try:
        if type(body) is not bytes or not 1 <= len(body) <= maximum:
            raise ValueError()
        value = json.loads(body.decode("utf-8"), object_pairs_hook=_unique,
                           parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
        if not isinstance(value, dict) or set(value) != set(fields):
            raise ValueError()
        return value
    except (ValueError, UnicodeError, RecursionError):
        raise AuthError() from None


class ActivationService:
    def __init__(self, auth, attestation, proofs, *, receipt_key):
        if type(receipt_key) is not bytes or len(receipt_key) != 32:
            raise ValueError("receipt_key_required")
        self.auth, self.attestation, self.proofs = auth, attestation, proofs
        self._receipt_cipher = AESGCM(receipt_key)

    @staticmethod
    def _receipt_id(operation, request_id):
        return operation + ":" + request_id

    @staticmethod
    def _receipt_aad(*, operation, request_id, request_digest, session_id,
                     response_access_digest, created_ms, retain_until_ms):
        return json.dumps({
            "created_ms": created_ms,
            "operation": operation,
            "request_digest": request_digest,
            "request_id": request_id,
            "response_access_digest": response_access_digest,
            "retain_until_ms": retain_until_ms,
            "session_id": session_id,
            "version": 1,
        }, sort_keys=True, separators=(",", ":")).encode("ascii")

    def _store_receipt(self, db, *, operation, request_id, request_digest, session_id,
                       response_access_digest, created_ms, retain_until_ms, tokens):
        if db.scalar(select(func.count()).select_from(response_receipts)) >= MAX_RECEIPTS:
            raise AuthError()
        nonce = secrets.token_bytes(12)
        plaintext = json.dumps({
            "access_expires_ms": tokens.access_expires_ms,
            "access_token": tokens.access_token,
            "refresh_expires_ms": tokens.refresh_expires_ms,
            "refresh_token": tokens.refresh_token,
            "subscription_expires_ms": tokens.subscription_expires_ms,
            "version": 1,
        }, sort_keys=True, separators=(",", ":")).encode("ascii")
        aad = self._receipt_aad(operation=operation, request_id=request_id,
            request_digest=request_digest, session_id=session_id,
            response_access_digest=response_access_digest, created_ms=created_ms,
            retain_until_ms=retain_until_ms)
        db.execute(response_receipts.insert().values(
            id=self._receipt_id(operation, request_id), operation=operation,
            request_id=request_id, session_id=session_id, created_ms=created_ms,
            retain_until_ms=retain_until_ms, nonce=nonce,
            ciphertext=self._receipt_cipher.encrypt(nonce, plaintext, aad)))

    def _restore_receipt(self, db, *, operation, request, request_digest, now):
        receipt = db.execute(select(response_receipts).where(
            response_receipts.c.id == self._receipt_id(operation, request["id"])
        )).mappings().first()
        if (receipt is None or receipt["operation"] != operation
                or receipt["request_id"] != request["id"]
                or receipt["session_id"] != request["session_id"]
                or not receipt["created_ms"] <= now < receipt["retain_until_ms"]):
            raise AuthError()
        aad = self._receipt_aad(operation=operation, request_id=request["id"],
            request_digest=request_digest, session_id=request["session_id"],
            response_access_digest=request["response_access_digest"],
            created_ms=receipt["created_ms"], retain_until_ms=receipt["retain_until_ms"])
        try:
            plaintext = self._receipt_cipher.decrypt(
                bytes(receipt["nonce"]), bytes(receipt["ciphertext"]), aad)
            payload = decode_body(plaintext, (
                "access_expires_ms", "access_token", "refresh_expires_ms",
                "refresh_token", "subscription_expires_ms", "version"), maximum=2048)
            if payload["version"] != 1:
                raise ValueError()
            access_digest = _digest(payload["access_token"], "access")
            refresh_digest = _digest(payload["refresh_token"], "refresh")
            if not secrets.compare_digest(access_digest, request["response_access_digest"]):
                raise ValueError()
            for field in ("access_expires_ms", "refresh_expires_ms"):
                _time(payload[field])
            if (payload["subscription_expires_ms"] is not None
                    and type(payload["subscription_expires_ms"]) is not int):
                raise ValueError()
        except (AuthError, InvalidTag, TypeError, ValueError):
            raise AuthError() from None

        rows = db.execute(select(credentials).where(
            credentials.c.digest.in_((access_digest, refresh_digest))
        )).mappings().all()
        current = {row["digest"]: row for row in rows}
        access = current.get(access_digest)
        refresh = current.get(refresh_digest)
        if any(row is None for row in (access, refresh)):
            raise AuthError()
        for row, kind, expires in (
                (access, "access", payload["access_expires_ms"]),
                (refresh, "refresh", payload["refresh_expires_ms"])):
            if (row["kind"] != kind or row["session_id"] != request["session_id"]
                    or row["consumed_ms"] is not None or row["expires_ms"] != expires):
                raise AuthError()
        if access["owner_id"] != refresh["owner_id"]:
            raise AuthError()
        subscription = db.execute(select(subscription_grants.c.expires_ms).where(
            subscription_grants.c.session_id == request["session_id"])).first()
        subscription_expires = subscription.expires_ms if subscription else None
        if payload["subscription_expires_ms"] != subscription_expires:
            raise AuthError()
        return Tokens(payload["access_token"], payload["refresh_token"],
                      payload["access_expires_ms"], payload["refresh_expires_ms"],
                      payload["subscription_expires_ms"])

    @staticmethod
    def _prune_receipts(db, now):
        db.execute(delete(response_receipts).where(response_receipts.c.retain_until_ms <= now))
        db.execute(delete(refresh_requests).where(
            refresh_requests.c.created_ms <= now - CHALLENGE_MS))

    def start(self, *, code, request_id, now_ms):
        _time(now_ms)
        _uuid(request_id)
        digest = _subscription_digest(code)
        with self.auth._write() as db:
            self._prune_receipts(db, now_ms)
            credential = self.auth._credential(db, digest, now_ms)
            if credential["consumed_ms"] is not None:
                raise AuthError()
            if db.execute(select(subscription_grants.c.code_digest).where(
                    subscription_grants.c.code_digest == digest)).first() is None:
                raise AuthError()
            db.execute(delete(activation_requests).where(activation_requests.c.retain_until_ms <= now_ms))
            row = db.execute(select(activation_requests).where(
                activation_requests.c.code_digest == digest)).mappings().first()
            if row:
                if row["id"] != request_id or not row["created_ms"] <= now_ms < row["expires_ms"]:
                    raise AuthError()
            else:
                if db.scalar(select(func.count()).select_from(activation_requests)) >= 1000:
                    raise AuthError()
                if db.execute(select(activation_requests.c.id).where(activation_requests.c.id == request_id)).first():
                    raise AuthError()
                expires = min(credential["expires_ms"], now_ms + CHALLENGE_MS)
                row = dict(id=request_id, code_digest=digest, challenge=secrets.token_bytes(32),
                           created_ms=now_ms, expires_ms=expires, retain_until_ms=expires)
                db.execute(activation_requests.insert().values(**row))
            return ActivationChallenge(request_id, base64.b64encode(row["challenge"]).decode("ascii"),
                                       row["expires_ms"], now_ms)

    @staticmethod
    def _request(db, identity, now):
        row = db.execute(select(activation_requests).where(activation_requests.c.id == identity)).mappings().first()
        if row is None or not row["created_ms"] <= now < row["retain_until_ms"]:
            raise AuthError()
        return row

    @staticmethod
    def _device(db, session_id, now):
        row = db.execute(select(attested_device_keys, sessions.c.expires_ms).join(
            sessions, sessions.c.id == attested_device_keys.c.session_id).where(
                sessions.c.id == session_id, sessions.c.revoked_ms.is_(None), sessions.c.expires_ms > now,
                attested_device_keys.c.revoked_ms.is_(None))).mappings().first()
        if row is None:
            raise AuthError()
        return row

    def complete(self, *, body, signature, issued_ms, nonce, now_ms):
        _time(now_ms)
        payload = decode_body(body, ("request_id", "certificate_chain"))
        identity = _uuid(payload["request_id"])
        encoded = payload["certificate_chain"]
        try:
            if not isinstance(encoded, list) or not 2 <= len(encoded) <= 8 or any(
                    not isinstance(c, str) or len(c) > 21_848 for c in encoded):
                raise ValueError()
            chain = [base64.b64decode(c, validate=True) for c in encoded]
            if any(base64.b64encode(c).decode("ascii") != original for c, original in zip(chain, encoded)):
                raise ValueError()
        except (ValueError, TypeError):
            raise AuthError() from None
        with self.auth.engine.connect() as db:
            row = self._request(db, identity, now_ms)
            if row["session_id"]:
                known = self._device(db, row["session_id"], now_ms)
                public_key = bytes(known["public_key_der"])
                attested = None
            else:
                credential = self.auth._credential(db, row["code_digest"], now_ms)
                if credential["consumed_ms"] is not None or now_ms >= row["expires_ms"]:
                    raise AuthError()
                try:
                    attested = self.attestation.verify(certificate_chain_der=chain,
                        expected_challenge=bytes(row["challenge"]), now_ms=now_ms)
                    public_key = attested.public_key_der
                except AttestationError:
                    raise AuthError() from None
        fingerprint = self.proofs.verify(public_key_der=public_key, signature=signature,
            method="POST", path=BASE + "/activation/complete", body=body, credential=identity,
            issued_ms=issued_ms, nonce=nonce, now_ms=now_ms)
        with self.auth._write() as db:
            self._prune_receipts(db, now_ms)
            row = self._request(db, identity, now_ms)
            if row["session_id"]:
                if self._device(db, row["session_id"], now_ms)["key_hash"] != fingerprint:
                    raise AuthError()
                tokens = self._restore_receipt(db, operation="activation", request=row,
                    request_digest=row["code_digest"], now=now_ms)
                session_id = row["session_id"]
            else:
                if attested is None or now_ms >= row["expires_ms"]:
                    raise AuthError()
                session = self.auth._enroll_verified(db, row["code_digest"], attested, now_ms,
                                                     require_subscription=True)
                tokens = self.auth._tokens(db, session, now_ms)
                session_id = session["id"]
                response_access_digest = _digest(tokens.access_token, "access")
                retain_until_ms = min(row["created_ms"] + RECOVERY_MS,
                                      tokens.subscription_expires_ms)
                db.execute(update(activation_requests).where(
                    activation_requests.c.id == identity).values(
                        session_id=session_id, response_access_digest=response_access_digest,
                        retain_until_ms=retain_until_ms))
                self._store_receipt(db, operation="activation", request_id=identity,
                    request_digest=row["code_digest"], session_id=session_id,
                    response_access_digest=response_access_digest, created_ms=row["created_ms"],
                    retain_until_ms=retain_until_ms, tokens=tokens)
            return tokens

    def refresh(self, *, body, key_fingerprint, signature, issued_ms, nonce, now_ms):
        _time(now_ms)
        payload = decode_body(body, ("request_id", "refresh_token"), maximum=1024)
        identity = _uuid(payload["request_id"])
        digest = _digest(payload["refresh_token"], "refresh")
        with self.auth.engine.connect() as db:
            receipt = db.execute(select(refresh_requests).where(
                refresh_requests.c.id == identity)).mappings().first()
            if receipt is not None:
                if (receipt["credential_digest"] != digest
                        or not receipt["created_ms"] <= now_ms < receipt["created_ms"] + CHALLENGE_MS):
                    raise AuthError()
                known = self._device(db, receipt["session_id"], now_ms)
            else:
                credential = self.auth._credential(db, digest, now_ms)
                known = self._device(db, credential["session_id"], now_ms)
            if known["key_hash"] != key_fingerprint:
                raise AuthError()
        self.proofs.verify(public_key_der=bytes(known["public_key_der"]), signature=signature,
            method="POST", path=BASE + "/session/refresh", body=body, credential=payload["refresh_token"],
            issued_ms=issued_ms, nonce=nonce, now_ms=now_ms)
        replay = False
        with self.auth._write() as db:
            receipt = db.execute(select(refresh_requests).where(refresh_requests.c.id == identity)).mappings().first()
            if receipt is not None:
                if receipt["credential_digest"] != digest or not receipt["created_ms"] <= now_ms < receipt["created_ms"] + CHALLENGE_MS:
                    raise AuthError()
                self._device(db, receipt["session_id"], now_ms)
                tokens = self._restore_receipt(db, operation="refresh", request=receipt,
                    request_digest=digest, now=now_ms)
            else:
                self._prune_receipts(db, now_ms)
                credential = self.auth._credential(db, digest, now_ms)
                self._device(db, credential["session_id"], now_ms)
                if credential["consumed_ms"] is not None:
                    db.execute(update(sessions).where(
                        sessions.c.id == credential["session_id"]).values(revoked_ms=now_ms))
                    replay = True
                else:
                    db.execute(update(credentials).where(
                        credentials.c.session_id == credential["session_id"],
                        credentials.c.consumed_ms.is_(None)).values(consumed_ms=now_ms))
                    session = self.auth._session(db, credential, now_ms)
                    tokens = self.auth._tokens(db, session, now_ms)
                    response_access_digest = _digest(tokens.access_token, "access")
                    retain_until_ms = min(now_ms + CHALLENGE_MS, session["expires_ms"])
                    db.execute(refresh_requests.insert().values(
                        id=identity, credential_digest=digest, session_id=session["id"],
                        created_ms=now_ms, response_access_digest=response_access_digest))
                    self._store_receipt(db, operation="refresh", request_id=identity,
                        request_digest=digest, session_id=session["id"],
                        response_access_digest=response_access_digest, created_ms=now_ms,
                        retain_until_ms=retain_until_ms, tokens=tokens)
        if replay:
            raise AuthError()
        return tokens

    def status(self, *, access_token, key_fingerprint, signature, issued_ms, nonce, now_ms):
        device = self.auth.authenticate_attested(access_token, key_fingerprint=key_fingerprint, now_ms=now_ms)
        self.proofs.verify(public_key_der=device.public_key_der, signature=signature, method="GET",
            path=BASE + "/session/status", body=b"", credential=access_token,
            issued_ms=issued_ms, nonce=nonce, now_ms=now_ms)
        # A revocation racing signature verification must not report active access.
        device = self.auth.authenticate_attested(access_token, key_fingerprint=key_fingerprint, now_ms=now_ms)
        return {"subscription_expires_ms": device.subscription_expires_ms,
                "server_time_ms": now_ms, "inference_enabled": False}
