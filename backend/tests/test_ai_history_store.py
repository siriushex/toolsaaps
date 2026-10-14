import importlib
import importlib.util
import sqlite3
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import pytest

from app.ai.history_contract import HistoryError, RETENTION_MS, encoded
from test_ai_history_contract import NOW, body, query, record

OWNER = "73e72e17-5568-4bcb-82a7-a82a195ef312"
KEY = "a1" * 32
OTHER = "b2" * 32
MASTER = bytes(range(32))


def store(tmp_path, **options):
    assert importlib.util.find_spec("app.ai.history_store") is not None, (
        "encrypted history store is absent"
    )
    module = importlib.import_module("app.ai.history_store")
    return module.HistoryStore(
        tmp_path / "history", master_key=MASTER, min_free_bytes=0, **options
    )


def read(value, key=KEY, **changes):
    return value.query(OWNER, key, encoded(query(**changes)), now_ms=NOW)


def test_persist_restart_exact_retry_and_device_owner_isolation(tmp_path):
    value = store(tmp_path)
    row = record()
    data = body([row])
    ack = value.append(OWNER, KEY, data, now_ms=NOW)
    assert ack["changedRecords"] == 1
    assert store(tmp_path).append(OWNER, KEY, data, now_ms=NOW + 1) == ack
    assert read(value)["records"][0]["record"] == row
    assert read(value, key=OTHER)["records"] == []
    assert value.query(str(uuid4()), KEY, encoded(query()), now_ms=NOW)["records"] == []
    assert len(list((tmp_path / "history").glob("*.sqlite"))) == 1


def test_ciphertext_has_no_raw_body_and_changed_batch_conflicts(tmp_path):
    value = store(tmp_path)
    row = record(
        kind="CHAT",
        data={
            "conversationId": str(uuid4()),
            "role": "USER",
            "text": "private synthetic phrase",
            "origin": "DEVICE_ARCHIVE",
        },
    )
    identity = str(uuid4())
    data = body([row], identity)
    value.append(OWNER, KEY, data, now_ms=NOW)
    file = next((tmp_path / "history").glob("*.sqlite"))
    assert b"private synthetic phrase" not in file.read_bytes()
    changed = body([row | {"data": row["data"] | {"text": "changed"}}], identity)
    with pytest.raises(HistoryError, match="history_conflict"):
        value.append(OWNER, KEY, changed, now_ms=NOW)


def test_conflict_rolls_back_other_rows_and_batch_receipt(tmp_path):
    value = store(tmp_path)
    old = record()
    value.append(OWNER, KEY, body([old]), now_ms=NOW)
    new = record()
    conflicting = old | {"data": {"glucoseMmol": 8.0}}
    with pytest.raises(HistoryError):
        value.append(OWNER, KEY, body([new, conflicting]), now_ms=NOW)
    assert [item["record"]["recordId"] for item in read(value)["records"]] == [
        old["recordId"]
    ]


def test_revision_identity_and_tombstone_no_resurrection(tmp_path):
    value = store(tmp_path)
    old = record()
    value.append(OWNER, KEY, body([old]), now_ms=NOW)
    updated = old | {"revision": 2, "data": {"glucoseMmol": 7.5}}
    value.append(OWNER, KEY, body([updated]), now_ms=NOW)
    assert read(value)["records"][0]["record"]["revision"] == 2
    for bad in (
        old,
        updated | {"revision": 3, "eventAtMs": old["eventAtMs"] - 1},
        updated | {"revision": 3, "source": "USER"},
    ):
        with pytest.raises(HistoryError):
            value.append(OWNER, KEY, body([bad]), now_ms=NOW)
    tombstone = updated | {"revision": 3, "deleted": True, "data": None}
    value.append(OWNER, KEY, body([tombstone]), now_ms=NOW)
    assert read(value)["records"][0]["record"]["deleted"] is True
    assert read(value, snapshotSequence=2)["records"] == []
    with pytest.raises(HistoryError):
        value.append(OWNER, KEY, body([updated | {"revision": 4}]), now_ms=NOW)
    dbfile = next((tmp_path / "history").glob("*.sqlite"))
    with sqlite3.connect(dbfile) as db:
        assert db.execute("SELECT count(*) FROM history_records").fetchone()[0] == 1


def test_stable_pagination_does_not_include_later_revisions(tmp_path):
    value = store(tmp_path)
    rows = [record() for _ in range(3)]
    value.append(OWNER, KEY, body(rows), now_ms=NOW)
    first = read(value, limit=1)
    assert first["hasMore"] and first["snapshotSequence"] == 3
    value.append(
        OWNER,
        KEY,
        body([rows[1] | {"revision": 2, "data": {"glucoseMmol": 8.2}}]),
        now_ms=NOW,
    )
    rest = read(
        value,
        afterSequence=first["nextAfterSequence"],
        snapshotSequence=first["snapshotSequence"],
    )
    assert [item["record"]["revision"] for item in rest["records"]] == [1, 1]
    assert read(value)["records"][-1]["record"]["revision"] == 2


def test_expiry_boundary_and_purge_do_not_extend_on_revision(tmp_path):
    value = store(tmp_path)
    old = record(eventAtMs=NOW - RETENTION_MS + 1, observedAtMs=NOW)
    value.append(OWNER, KEY, body([old]), now_ms=NOW)
    value.append(OWNER, KEY, body([old | {"revision": 2}]), now_ms=NOW)
    result = value.query(OWNER, KEY, encoded(query()), now_ms=NOW + 1)
    assert result["records"] == []
    value.purge(now_ms=NOW + 1)
    dbfile = next((tmp_path / "history").glob("*.sqlite"))
    with sqlite3.connect(dbfile) as db:
        assert db.execute("SELECT count(*) FROM history_records").fetchone()[0] == 0


def test_concurrent_exact_retries_have_one_commit(tmp_path):
    value = store(tmp_path)
    data = body()
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(
            pool.map(lambda _: value.append(OWNER, KEY, data, now_ms=NOW), range(8))
        )
    assert all(item == results[0] for item in results)
    assert len(read(value)["records"]) == 1


def test_quotas_and_wrong_key_fail_closed(tmp_path):
    value = store(tmp_path, max_rows=1, max_devices=1)
    value.append(OWNER, KEY, body(), now_ms=NOW)
    with pytest.raises(HistoryError, match="history_capacity_reached"):
        value.append(OWNER, KEY, body(), now_ms=NOW)
    with pytest.raises(HistoryError, match="history_capacity_reached"):
        value.append(OWNER, OTHER, body(), now_ms=NOW)
    module = importlib.import_module("app.ai.history_store")
    wrong = module.HistoryStore(
        tmp_path / "history", master_key=b"z" * 32, min_free_bytes=0
    )
    with pytest.raises(HistoryError, match="history_storage_unavailable"):
        read(wrong)


def test_symlink_and_foreign_database_refused(tmp_path):
    root = tmp_path / "outside"
    root.mkdir()
    alias = tmp_path / "alias"
    alias.symlink_to(root, target_is_directory=True)
    value = store(tmp_path)
    module = importlib.import_module("app.ai.history_store")
    with pytest.raises(ValueError):
        module.HistoryStore(alias, master_key=MASTER)
    value.append(OWNER, KEY, body(), now_ms=NOW)
    file = next((tmp_path / "history").glob("*.sqlite"))
    file.unlink()
    file.symlink_to(tmp_path / "outside.sqlite")
    with pytest.raises(HistoryError):
        read(value)
    assert not (tmp_path / "outside.sqlite").exists()


def test_record_tamper_and_disk_pressure_are_not_acknowledged(tmp_path, monkeypatch):
    value = store(tmp_path)
    value.append(OWNER, KEY, body(), now_ms=NOW)
    file = next((tmp_path / "history").glob("*.sqlite"))
    with sqlite3.connect(file) as db:
        db.execute("UPDATE history_records SET quality='OK'")
    with pytest.raises(HistoryError, match="history_storage_unavailable"):
        read(value)
    module = importlib.import_module("app.ai.history_store")
    import shutil

    monkeypatch.setattr(
        module.shutil, "disk_usage", lambda _: shutil._ntuple_diskusage(10, 10, 0)
    )
    constrained = module.HistoryStore(
        tmp_path / "constrained", master_key=MASTER, min_free_bytes=1
    )
    with pytest.raises(HistoryError):
        constrained.append(OWNER, KEY, body(), now_ms=NOW)
    assert not list((tmp_path / "constrained").glob("*.sqlite"))


@pytest.mark.parametrize("field,corrupted", [("ciphertext", b"x"), ("nonce", 1)])
def test_corrupt_latest_record_is_not_deduplicated_as_healthy(
    tmp_path, field, corrupted
):
    value = store(tmp_path)
    row = record()
    value.append(OWNER, KEY, body([row]), now_ms=NOW)
    file = next((tmp_path / "history").glob("*.sqlite"))
    with sqlite3.connect(file) as db:
        db.execute("UPDATE history_records SET " + field + "=?", (corrupted,))
    with pytest.raises(HistoryError, match="history_storage_unavailable"):
        value.append(OWNER, KEY, body([row]), now_ms=NOW)


def test_response_budget_and_restore_preserve_pagination(tmp_path):
    value = store(tmp_path)
    rows = [
        record(
            kind="CHAT",
            data={
                "conversationId": str(uuid4()),
                "role": "USER",
                "text": "s" * 7000,
                "origin": "DEVICE_ARCHIVE",
            },
        )
        for _ in range(20)
    ]
    value.append(OWNER, KEY, body(rows[:10]), now_ms=NOW)
    value.append(OWNER, KEY, body(rows[10:]), now_ms=NOW)
    seen = []
    page = read(value)
    snapshot = page["snapshotSequence"]
    while True:
        assert len(encoded(page)) <= 65536
        seen.extend(item["record"]["recordId"] for item in page["records"])
        if not page["hasMore"]:
            break
        page = read(
            value, afterSequence=page["nextAfterSequence"], snapshotSequence=snapshot
        )
    assert seen == [row["recordId"] for row in rows]
    import shutil

    restored_root = tmp_path / "restored"
    restored_root.mkdir(mode=0o700)
    source = next((tmp_path / "history").glob("*.sqlite"))
    destination = restored_root / source.name
    shutil.copyfile(source, destination)
    destination.chmod(0o600)
    module = importlib.import_module("app.ai.history_store")
    restored = module.HistoryStore(restored_root, master_key=MASTER, min_free_bytes=0)
    assert read(restored)["snapshotSequence"] == snapshot


def test_byte_quota_rolls_back_and_different_batches_deduplicate(tmp_path):
    value = store(tmp_path, max_bytes=512)
    with pytest.raises(HistoryError, match="history_capacity_reached"):
        value.append(OWNER, KEY, body([record(), record()]), now_ms=NOW)
    assert read(value)["records"] == []
    roomy = store(tmp_path)
    row = record()
    first = roomy.append(OWNER, KEY, body([row]), now_ms=NOW)
    second = roomy.append(OWNER, KEY, body([row]), now_ms=NOW)
    assert first["sequence"] == second["sequence"]
    assert second["changedRecords"] == 0


def test_foreign_schema_is_never_overwritten(tmp_path):
    value = store(tmp_path)
    value.append(OWNER, KEY, body(), now_ms=NOW)
    file = next(value.root.glob("*.sqlite"))
    file.unlink()
    with sqlite3.connect(file) as db:
        db.execute("CREATE TABLE unrelated(value TEXT)")
        db.execute("INSERT INTO unrelated VALUES('preserve')")
    file.chmod(0o600)
    before = file.read_bytes()
    with pytest.raises(HistoryError, match="history_storage_unavailable"):
        read(value)
    with sqlite3.connect(file) as db:
        assert db.execute("SELECT value FROM unrelated").fetchone()[0] == "preserve"
    assert file.read_bytes() == before


def test_concurrent_conflicting_revisions_have_one_winner(tmp_path):
    value = store(tmp_path)
    row = record()
    value.append(OWNER, KEY, body([row]), now_ms=NOW)

    def write(number):
        try:
            value.append(
                OWNER,
                KEY,
                body([row | {"revision": 2, "data": {"glucoseMmol": float(number)}}]),
                now_ms=NOW,
            )
            return "committed"
        except HistoryError as failure:
            return failure.code

    with ThreadPoolExecutor(max_workers=2) as pool:
        outcomes = list(pool.map(write, [7, 8]))
    assert sorted(outcomes) == ["committed", "history_conflict"]
    assert len(read(value)["records"]) == 1


def test_query_metadata_failure_closes_connection(tmp_path, monkeypatch):
    value = store(tmp_path)
    value.append(OWNER, KEY, body(), now_ms=NOW)
    original = value._open
    opened = []

    class Connection:
        def __init__(self, db):
            self.db, self.closed = db, False

        def close(self):
            self.closed = True
            self.db.close()

    def opening(*args, **kwargs):
        item = Connection(original(*args, **kwargs))
        opened.append(item)
        return item

    def fail(_):
        raise sqlite3.DatabaseError("synthetic metadata failure")

    monkeypatch.setattr(value, "_open", opening)
    monkeypatch.setattr(value, "_highwater", fail)
    try:
        with pytest.raises(HistoryError):
            read(value)
        assert all(item.closed for item in opened)
    finally:
        for item in opened:
            item.db.close()


def test_expired_retry_cannot_extend_record_or_receipt_lifetime(tmp_path):
    value = store(tmp_path)
    data = body([record(eventAtMs=NOW - RETENTION_MS + 1)])
    value.append(OWNER, KEY, data, now_ms=NOW)
    with pytest.raises(HistoryError, match="invalid_history_request"):
        value.append(OWNER, KEY, data, now_ms=NOW + 1)
    value.purge(now_ms=NOW + RETENTION_MS)
    file = next(value.root.glob("*.sqlite"))
    with sqlite3.connect(file) as db:
        assert db.execute("SELECT count(*) FROM history_records").fetchone()[0] == 0
        assert db.execute("SELECT count(*) FROM history_receipts").fetchone()[0] == 0
