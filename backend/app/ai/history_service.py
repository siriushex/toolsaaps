"""Explicit owner-scoped history access; no model, worker or therapy dependency."""

from .client_auth import AuthError, AttestedDevice
from .history_contract import canonical_uuid
from .history_store import HistoryStore


class HistoryService:
    def __init__(self, auth, proofs, store: HistoryStore, *, owner_id: str):
        canonical_uuid(owner_id)
        if not isinstance(store, HistoryStore):
            raise ValueError("history_store_required")
        self.auth, self.proofs, self.store, self.owner_id = (
            auth,
            proofs,
            store,
            owner_id,
        )

    def preauthorize(
        self, access_token: str, key_fingerprint: str, now_ms: int
    ) -> AttestedDevice:
        device = self.auth.authenticate_attested(
            access_token, key_fingerprint=key_fingerprint, now_ms=now_ms
        )
        if (
            device.owner_id != self.owner_id
            or device.subscription_expires_ms is None
            or now_ms >= device.subscription_expires_ms
        ):
            raise AuthError()
        return device

    def verify_request(
        self,
        *,
        preauthorized,
        access_token,
        key_fingerprint,
        method,
        path,
        body,
        signature,
        issued_ms,
        nonce,
        now_ms,
    ):
        if (
            preauthorized.key_fingerprint != key_fingerprint
            or not preauthorized.session_id
        ):
            raise AuthError()
        self.proofs.verify(
            public_key_der=preauthorized.public_key_der,
            signature=signature,
            method=method,
            path=path,
            body=body,
            credential=access_token,
            issued_ms=issued_ms,
            nonce=nonce,
            now_ms=now_ms,
        )
        current = self.preauthorize(access_token, key_fingerprint, now_ms)
        if (
            current.session_id != preauthorized.session_id
            or current.owner_id != preauthorized.owner_id
        ):
            raise AuthError()
        return current
