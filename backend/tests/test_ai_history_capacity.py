"""Synthetic only. Opt in to the year check with HISTORY_CAPACITY_DAYS=365.

Run with the backend test dependencies and PYTHONPATH=backend:
python -m pytest -q -s backend/tests/test_ai_history_capacity.py
This measures this host, not admission of a production server or clinical data.
"""

import hashlib
import json
import os
import secrets
import sqlite3
import sys
import time
from uuid import UUID

import pytest

from app.ai.history_contract import DAY_MS, RETENTION_MS, decode_batch, encoded
from app.ai.history_store import META_FIELDS
from test_ai_history_contract import NOW, body, record
from test_ai_history_store import KEY, OWNER, store


def populate_synthetic(value, days):
    """Bulk fixture: validate every batch, use real per-device encryption/quota.

    HTTP/proof/retry receipts are outside this context-reader capacity check.
    No unencrypted projection or alternative schema is used.
    """
    samples = days * DAY_MS // 300_000
    db = value._open(OWNER, KEY, create=True)
    cipher = value._cipher(OWNER, KEY)
    sequence = 0

    def insert(rows):
        nonlocal sequence
        batch = decode_batch(body(rows), now_ms=NOW)
        values = []
        for item in batch.records:
            sequence += 1
            plain = encoded(item.model_dump(exclude_unset=True))
            metadata = (sequence, item.recordId, item.revision, item.eventAtMs,
                        item.observedAtMs, item.kind, item.source, item.quality,
                        int(item.deleted), item.eventAtMs + RETENTION_MS, NOW,
                        hashlib.sha256(plain).hexdigest())
            nonce = secrets.token_bytes(12)
            ciphertext = cipher.encrypt(nonce, plain, value._aad(OWNER, KEY, "record", list(metadata)))
            values.append((*metadata, nonce, ciphertext))
        db.executemany("INSERT INTO history_records VALUES("
                       + ",".join("?" for _ in range(len(META_FIELDS) + 2)) + ")", values)

    try:
        db.execute("BEGIN IMMEDIATE")
        rows = []
        for index in range(samples):
            at = NOW - index * 300_000
            for kind in ("GLUCOSE", "FORECAST"):
                identity = index * 2 + (1 if kind == "GLUCOSE" else 2)
                rows.append(record(recordId=str(UUID(int=identity)), eventAtMs=at,
                    observedAtMs=at, kind=kind, source="CGM" if kind == "GLUCOSE" else "COPILOT",
                    data={"glucoseMmol": 6.4} if kind == "GLUCOSE" else dict(
                        horizonMinutes=30, valueMmol=7.0, ciLowMmol=4.0,
                        ciHighMmol=10.0, modelVersion="synthetic-v1")))
                if len(rows) == 32:
                    insert(rows)
                    rows = []
        rows.append(record(recordId=str(UUID(int=samples * 2 + 1)), kind="CHAT", source="USER",
            data=dict(conversationId=str(UUID(int=samples * 2 + 2)), role="USER",
                      text="Synthetic capacity question", origin="DEVICE_ARCHIVE")))
        insert(rows)
        value._check_quota(db)
        db.commit()
    finally:
        db.close()
    return samples


def assert_complete(result, samples):
    counts = {"GLUCOSE": samples, "FORECAST": samples, "CHAT": 1}
    coverage = result["coverage"]
    assert coverage["eligibleRecords"] == samples * 2 + 1
    assert coverage["countsByKind"] == counts
    assert coverage["countsByQuality"] == {"UNKNOWN": samples * 2 + 1}
    assert coverage["totalsComplete"] is True and coverage["expectedSamplesUnknown"] is True
    assert coverage["firstAtMs"] == NOW - (samples - 1) * 300_000
    assert coverage["lastAtMs"] == NOW
    assert sum(sum(counts.values()) for _, counts in result["dailyCounts"]) == samples * 2 + 1
    assert result["omitted"]["dailyBuckets"] == result["omitted"]["glucoseRecords"] == 0
    cohort, = result["glucoseCohorts"]
    assert cohort["count"] == samples and cohort["meanMmol"] == 6.4
    assert result["omitted"]["recentByKind"] == {
        "GLUCOSE": samples - 12, "FORECAST": samples - 12, "CHAT": 0}
    assert len(result["recentRecords"]) == 25
    assert result["snapshotSequence"] == samples * 2 + 1


def test_small_synthetic_capacity_uses_the_real_encrypted_reader(tmp_path):
    value = store(tmp_path)
    samples = populate_synthetic(value, 2)
    assert_complete(value.context_snapshot(OWNER, KEY, now_ms=NOW), samples)
    assert_complete(value.context_snapshot(OWNER, KEY, now_ms=NOW + 1), samples)


@pytest.mark.skipif(not os.environ.get("HISTORY_CAPACITY_DAYS"), reason="opt-in synthetic capacity check")
def test_synthetic_year_capacity_with_unchanged_reader_budgets(tmp_path):
    days = int(os.environ["HISTORY_CAPACITY_DAYS"])
    assert 1 <= days <= 365
    value = store(tmp_path)
    samples = populate_synthetic(value, days)
    durations = {}
    for label, reader in (("cold", value), ("verified_cache", value), ("restart", store(tmp_path))):
        start = time.monotonic()
        result = reader.context_snapshot(OWNER, KEY, now_ms=NOW + 1)
        durations[label] = round(time.monotonic() - start, 4)
        assert_complete(result, samples)
    # Another store's revision must trigger a current cold read, preserving all
    # counts. This also exercises invalidation without an in-process write hook.
    writer = store(tmp_path)
    writer.append(OWNER, KEY, body([record(recordId=str(UUID(int=1)), eventAtMs=NOW,
        observedAtMs=NOW, revision=2, data={"glucoseMmol": 8.4})]), now_ms=NOW + 1)
    start = time.monotonic()
    revised = value.context_snapshot(OWNER, KEY, now_ms=NOW + 1)
    durations["other_writer_revision"] = round(time.monotonic() - start, 4)
    assert revised["coverage"] == result["coverage"]
    assert revised["snapshotSequence"] == samples * 2 + 2
    assert revised["glucoseCohorts"][0]["maxMmol"] == 8.4
    print(json.dumps(dict(days=days, encryptedRecords=samples * 2 + 1,
                         seconds=durations, python=sys.version.split()[0], sqlite=sqlite3.sqlite_version)))
