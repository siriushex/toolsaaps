"""Internal server conversation records, never a client ingestion contract."""
from __future__ import annotations

import hashlib
import json
import re

from .history_contract import RETENTION_MS, canonical_uuid, encoded, valid_time
from .job_ledger import Job
from .job_policy import JobPolicy
from .schemas import ChatRequest, ChatResult


MAX_ENTRY_BYTES = 131_072
MAX_CONTEXT_BYTES = 16_384
FINAL_STATES = frozenset({"SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED", "UNKNOWN"})
META_FIELDS = ("job_id", "created_ms", "expires_ms", "updated_ms", "budget_bytes")
SCHEMA = (
    "CREATE TABLE server_chat(job_id TEXT PRIMARY KEY,created_ms INTEGER NOT NULL,"
    "expires_ms INTEGER NOT NULL,updated_ms INTEGER NOT NULL,budget_bytes INTEGER NOT NULL,"
    "nonce BLOB NOT NULL,ciphertext BLOB NOT NULL)",
    "CREATE INDEX server_chat_created ON server_chat(created_ms,job_id)",
    "CREATE INDEX server_chat_expiry ON server_chat(expires_ms)",
)


def binding(job: Job):
    if not isinstance(job, Job) or job.kind != "CHAT":
        raise ValueError("invalid_chat_job")
    for value in (job.id, job.owner_id, job.session_id, job.request_id):
        canonical_uuid(value)
    for value in (job.key_hash, job.digest):
        if type(value) is not str or not re.fullmatch(r"[0-9a-f]{64}", value):
            raise ValueError("invalid_chat_job")
    valid_time(job.created_ms)
    valid_time(job.deadline_ms)
    if not job.created_ms < job.deadline_ms <= job.created_ms + 900_000:
        raise ValueError("invalid_chat_job")
    return dict(jobId=job.id, requestId=job.request_id, requestDigest=job.digest,
                routeRevision=job.route_revision, acceptedAtMs=job.created_ms,
                deadlineMs=job.deadline_ms)


def accepted_entry(job: Job, request: ChatRequest, policy: JobPolicy):
    if (not isinstance(request, ChatRequest) or not isinstance(policy, JobPolicy)
            or policy.kind != "CHAT" or policy.route_revision != job.route_revision
            or policy.allow_tools or policy.allow_actions):
        raise ValueError("invalid_chat_archive_input")
    ChatRequest.decode(encoded(dict(text=request.text)))
    return dict(binding(job), origin="SERVER_ARCHIVE", model=policy.model,
                promptRevision=policy.prompt_revision,
                instructionsDigest=hashlib.sha256(policy.system_prompt.encode()).hexdigest(),
                question=request.text, state="OUTCOME_NOT_RECORDED", answer=None,
                startedAtMs=None, finishedAtMs=None)


def final_entry(entry: dict, job: Job, result: ChatResult | None):
    if any(entry.get(key) != value for key, value in binding(job).items()):
        raise ValueError("chat_archive_binding_mismatch")
    if job.state not in FINAL_STATES:
        raise ValueError("chat_outcome_not_final")
    valid_time(job.finished_ms)
    if job.finished_ms < job.created_ms:
        raise ValueError("chat_archive_clock_regression")
    if job.started_ms is not None:
        valid_time(job.started_ms)
        if not job.created_ms <= job.started_ms <= job.finished_ms:
            raise ValueError("chat_archive_clock_regression")
    if job.state == "SUCCEEDED":
        if (not isinstance(result, ChatResult) or job.started_ms is None
                or job.finished_ms >= job.deadline_ms):
            raise ValueError("chat_result_not_settled")
        answer = ChatResult.decode(result.as_dict()).text
    else:
        if result is not None:
            raise ValueError("chat_result_not_settled")
        answer = None
    return entry | dict(state=job.state, answer=answer, startedAtMs=job.started_ms,
                        finishedAtMs=job.finished_ms)


def encrypt_entry(store, owner, device, entry, *, updated_ms, pending):
    import secrets

    plain = encoded(entry)
    if len(plain) + 16 > MAX_ENTRY_BYTES:
        raise ValueError("chat_archive_entry_too_large")
    values = dict(job_id=entry["jobId"], created_ms=entry["acceptedAtMs"],
                  expires_ms=entry["acceptedAtMs"] + RETENTION_MS, updated_ms=updated_ms,
                  budget_bytes=MAX_ENTRY_BYTES if pending else len(plain) + 16)
    nonce = secrets.token_bytes(12)
    values.update(nonce=nonce, ciphertext=store._cipher(owner, device).encrypt(
        nonce, plain, store._aad(owner, device, "server-chat", [values[key] for key in META_FIELDS])))
    return values


def decrypt_entry(store, owner, device, row):
    plain = store._cipher(owner, device).decrypt(row["nonce"], row["ciphertext"],
        store._aad(owner, device, "server-chat", [row[key] for key in META_FIELDS]))
    entry = json.loads(plain)
    if (len(plain) + 16 > MAX_ENTRY_BYTES or entry["jobId"] != row["job_id"]
            or entry["acceptedAtMs"] != row["created_ms"]
            or entry["origin"] != "SERVER_ARCHIVE"
            or row["expires_ms"] != row["created_ms"] + RETENTION_MS):
        raise ValueError("invalid_chat_archive_entry")
    return entry


def context_entry(entry):
    # Internal request/policy digests are not useful model input. Long messages
    # are excerpts with explicit flags; never split or truncate stored originals.
    result = {key: entry[key] for key in (
        "jobId", "acceptedAtMs", "startedAtMs", "finishedAtMs", "state", "model")}
    for key in ("question", "answer"):
        text = entry[key]
        excerpt = text[:1200] if text is not None else None
        while excerpt and len(encoded(excerpt)) > 4000:
            excerpt = excerpt[:len(excerpt) // 2]
        result[key] = excerpt
        result[key + "Truncated"] = text != excerpt
    return result
