"""Closed, read-only server archive query and device-bound continuation tokens."""
from __future__ import annotations

import base64
import secrets
from typing import Annotated

from cryptography.exceptions import InvalidTag
from pydantic import Field, model_validator

from .history_contract import (
    Closed, HistoryError, RETENTION_MS, Time, _decode, canonical_uuid, encoded, valid_time,
)

MAX_QUERY_BYTES = 4096
MAX_RESPONSE_BYTES = 131_072
CURSOR_TTL_MS = 900_000
REVISION = "server-chat-archive-v1"
FIELDS = {"fromMs", "untilMs", "cursor", "limit"}


class ChatArchiveQuery(Closed):
    fromMs: Time
    untilMs: Time
    cursor: Annotated[str, Field(min_length=40, max_length=1024, pattern=r"^[A-Za-z0-9_-]+={0,2}$")] | None
    limit: Annotated[int, Field(ge=1, le=20)]

    @model_validator(mode="after")
    def window(self):
        if not 0 < self.untilMs - self.fromMs <= RETENTION_MS + 1:
            raise ValueError("invalid_window")
        return self


class ArchiveCursor(Closed):
    upperRow: Annotated[int, Field(gt=0, le=2**63 - 1)]
    upperJob: str
    afterRow: Annotated[int, Field(ge=0, le=2**63 - 1)]
    issuedMs: Time

    @model_validator(mode="after")
    def bounds(self):
        canonical_uuid(self.upperJob)
        if self.afterRow > self.upperRow:
            raise ValueError("invalid_cursor")
        return self


def decode_query(body: bytes, *, now_ms: int):
    try:
        valid_time(now_ms)
        if type(body) is not bytes or len(body) > MAX_QUERY_BYTES:
            raise ValueError("body_limit")
        query = _decode(body, ChatArchiveQuery)
        grace = CURSOR_TTL_MS if query.cursor is not None else 0
        if query.fromMs < max(1, now_ms - RETENTION_MS - grace) or query.untilMs > now_ms + 1:
            raise ValueError("outside_retention")
        return query
    except (ValueError, TypeError, UnicodeError, RecursionError):
        raise HistoryError(400, "invalid_chat_query") from None


def encode_cursor(store, owner, device, query, cursor: ArchiveCursor):
    nonce = secrets.token_bytes(12)
    aad = store._aad(owner, device, "server-chat-cursor", [REVISION, query.fromMs, query.untilMs])
    ciphertext = store._cipher(owner, device).encrypt(nonce, encoded(cursor.model_dump()), aad)
    return base64.urlsafe_b64encode(nonce + ciphertext).decode("ascii")


def decode_cursor(store, owner, device, query, *, now_ms):
    if query.cursor is None:
        return None
    try:
        raw = base64.b64decode(query.cursor, altchars=b"-_", validate=True)
        if len(raw) < 29 or base64.urlsafe_b64encode(raw).decode("ascii") != query.cursor:
            raise ValueError("invalid_cursor")
        aad = store._aad(owner, device, "server-chat-cursor", [REVISION, query.fromMs, query.untilMs])
        plain = store._cipher(owner, device).decrypt(raw[:12], raw[12:], aad)
        cursor = _decode(plain, ArchiveCursor)
        if now_ms < cursor.issuedMs:
            raise ValueError("clock_regression")
    except (InvalidTag, ValueError, TypeError, UnicodeError, RecursionError):
        raise HistoryError(400, "invalid_chat_cursor") from None
    if now_ms >= cursor.issuedMs + CURSOR_TTL_MS:
        raise HistoryError(409, "chat_cursor_expired")
    return cursor


def public_entry(entry, *, updated_ms):
    # Exclude internal request/policy digests; preserve complete user-facing text.
    value = {key: entry[key] for key in (
        "jobId", "requestId", "acceptedAtMs", "deadlineMs", "startedAtMs", "finishedAtMs",
        "state", "question", "answer", "model", "promptRevision")}
    value["updatedAtMs"] = updated_ms
    return value
