from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import pytest


def new_request(**changes):
    return dict(owner_id=str(uuid4()), request_id=str(uuid4()), kind="CHAT",
                digest="a" * 64, route_revision="pilot-v1", deadline_ms=10000,
                now_ms=1000) | changes


def make_ledger(tmp_path, **limits):
    from app.ai.job_ledger import JobLedger
    return JobLedger(tmp_path / "jobs.sqlite", **limits)


def test_ledger_module_exists():
    import importlib.util
    assert importlib.util.find_spec("app.ai.job_ledger") is not None


def test_duplicate_reservation_persists_across_reopen(tmp_path):
    request = new_request()
    first = make_ledger(tmp_path).reserve(**request)
    second = make_ledger(tmp_path).reserve(**request)
    assert first.id == second.id
    assert second.state == "QUEUED"


@pytest.mark.parametrize("changes", [{"digest": "b" * 64}, {"route_revision": "pilot-v2"},
                                    {"deadline_ms": 9999}])
def test_duplicate_content_or_policy_conflict(tmp_path, changes):
    from app.ai.job_ledger import LedgerError
    ledger = make_ledger(tmp_path)
    request = new_request()
    ledger.reserve(**request)
    with pytest.raises(LedgerError, match="request_conflict"):
        ledger.reserve(**(request | changes))


def test_owner_cannot_read_cancel_or_claim_another_job(tmp_path):
    ledger = make_ledger(tmp_path)
    job = ledger.reserve(**new_request())
    other = str(uuid4())
    assert ledger.get(other, job.id) is None
    assert ledger.cancel(other, job.id, now_ms=2000) is None
    assert ledger.claim(other, job.id, now_ms=2000) is None
    assert ledger.get(job.owner_id, job.id).state == "QUEUED"


def test_same_request_id_scoped_to_owner(tmp_path):
    ledger = make_ledger(tmp_path)
    request = new_request()
    first = ledger.reserve(**request)
    second = ledger.reserve(**(request | {"owner_id": str(uuid4())}))
    assert first.id != second.id


def test_one_active_job_per_owner(tmp_path):
    from app.ai.job_ledger import LedgerError
    ledger = make_ledger(tmp_path)
    request = new_request()
    ledger.reserve(**request)
    with pytest.raises(LedgerError, match="owner_busy"):
        ledger.reserve(**(request | {"request_id": str(uuid4())}))


def test_concurrent_same_request_reserves_once(tmp_path):
    ledger = make_ledger(tmp_path)
    request = new_request()
    with ThreadPoolExecutor(max_workers=8) as pool:
        jobs = list(pool.map(lambda _: ledger.reserve(**request), range(16)))
    assert len({job.id for job in jobs}) == 1


def test_queue_limit_atomic_across_instances(tmp_path):
    from app.ai.job_ledger import LedgerError
    ledgers = [make_ledger(tmp_path, max_waiting=2) for _ in range(8)]

    def reserve(ledger):
        try:
            return ledger.reserve(**new_request()).state
        except LedgerError as exc:
            return str(exc)

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(reserve, ledgers))
    assert results.count("QUEUED") == 2
    assert results.count("queue_full") == 6


def test_only_one_process_claim_at_a_time(tmp_path):
    ledger = make_ledger(tmp_path)
    jobs = [ledger.reserve(**new_request()) for _ in range(3)]
    with ThreadPoolExecutor(max_workers=3) as pool:
        claims = list(pool.map(lambda j: ledger.claim(j.owner_id, j.id, now_ms=2000), jobs))
    assert sum(claim is not None for claim in claims) == 1


def test_running_cancel_holds_slot_until_stopped_receipt(tmp_path):
    from app.ai.job_ledger import LedgerError
    ledger = make_ledger(tmp_path)
    job, other = [ledger.reserve(**new_request()) for _ in range(2)]
    claim = ledger.claim(job.owner_id, job.id, now_ms=2000)
    assert ledger.cancel(job.owner_id, job.id, now_ms=2100).state == "CANCEL_REQUESTED"
    assert ledger.claim(other.owner_id, other.id, now_ms=2200) is None
    with pytest.raises(LedgerError, match="invalid_transition"):
        ledger.finish(job.owner_id, job.id, claim.token, "SUCCEEDED", now_ms=2300)
    result = ledger.finish(job.owner_id, job.id, claim.token, "CANCELLED", now_ms=2300)
    assert result.state == "CANCELLED"
    assert ledger.claim(other.owner_id, other.id, now_ms=2400) is not None


def test_stale_or_wrong_worker_cannot_complete(tmp_path):
    from app.ai.job_ledger import LedgerError
    ledger = make_ledger(tmp_path)
    job = ledger.reserve(**new_request())
    ledger.claim(job.owner_id, job.id, now_ms=2000)
    with pytest.raises(LedgerError, match="invalid_claim"):
        ledger.finish(job.owner_id, job.id, str(uuid4()), "SUCCEEDED", now_ms=2100)


def test_terminal_receipt_never_reexecutes_without_payload_storage(tmp_path):
    ledger = make_ledger(tmp_path)
    request = new_request()
    job = ledger.reserve(**request)
    claim = ledger.claim(job.owner_id, job.id, now_ms=2000)
    ledger.finish(job.owner_id, job.id, claim.token, "SUCCEEDED", now_ms=3000)
    fresh = make_ledger(tmp_path)
    duplicate = fresh.reserve(**(request | {"now_ms": 20000}))
    assert duplicate.id == job.id and duplicate.state == "SUCCEEDED"
    assert fresh.claim(job.owner_id, job.id, now_ms=20000) is None
    assert "payload" not in duplicate.__dict__ and "result" not in duplicate.__dict__


def test_expired_queued_jobs_release_quota_but_running_jobs_do_not(tmp_path):
    ledger = make_ledger(tmp_path, max_waiting=1)
    queued = ledger.reserve(**new_request(deadline_ms=2000))
    other = ledger.reserve(**new_request(now_ms=2000))
    assert ledger.get(queued.owner_id, queued.id).state == "EXPIRED"
    claim = ledger.claim(other.owner_id, other.id, now_ms=2001)
    assert claim is not None
    next_job = ledger.reserve(**new_request(now_ms=12000, deadline_ms=13000))
    assert ledger.claim(next_job.owner_id, next_job.id, now_ms=12001) is None


@pytest.mark.parametrize("changes", [{"owner_id": "email@example.com"}, {"digest": "bad"},
    {"kind": "DOSE"}, {"deadline_ms": 1000}, {"now_ms": True}, {"route_revision": "../secret"},
    {"digest": None}, {"kind": []}, {"route_revision": None}])
def test_invalid_metadata_rejected(tmp_path, changes):
    with pytest.raises(ValueError):
        make_ledger(tmp_path).reserve(**(new_request() | changes))


def test_does_not_attach_to_therapy_database(tmp_path):
    import sqlite3
    from app.ai.job_ledger import JobLedger
    path = tmp_path / "therapy.sqlite"
    with sqlite3.connect(path) as db:
        db.execute("CREATE TABLE glucose (value REAL)")
    with pytest.raises(ValueError, match="dedicated_database_required"):
        JobLedger(path)
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall() == [("glucose",)]


def test_repeated_expired_request_is_not_reported_as_queued(tmp_path):
    ledger = make_ledger(tmp_path)
    request = new_request(deadline_ms=2000)
    job = ledger.reserve(**request)
    duplicate = ledger.reserve(**(request | {"now_ms": 2001}))
    assert duplicate.id == job.id and duplicate.state == "EXPIRED"


def test_late_success_is_expired_not_deliverable(tmp_path):
    ledger = make_ledger(tmp_path)
    job = ledger.reserve(**new_request(deadline_ms=2000))
    claim = ledger.claim(job.owner_id, job.id, now_ms=1500)
    result = ledger.finish(job.owner_id, job.id, claim.token, "SUCCEEDED", now_ms=2000)
    assert result.state == "EXPIRED"


def test_unknown_and_cancelled_jobs_are_never_reclaimed_after_reopen(tmp_path):
    ledger = make_ledger(tmp_path)
    requests = [new_request(), new_request()]
    first, second = [ledger.reserve(**request) for request in requests]
    claim = ledger.claim(first.owner_id, first.id, now_ms=1200)
    ledger.finish(first.owner_id, first.id, claim.token, "UNKNOWN", now_ms=1500)
    ledger.cancel(second.owner_id, second.id, now_ms=1500)
    reopened = make_ledger(tmp_path)
    for request, state in zip(requests, ["UNKNOWN", "CANCELLED"]):
        job = reopened.reserve(**(request | {"now_ms": 1600}))
        assert job.state == state
        assert reopened.claim(job.owner_id, job.id, now_ms=1700) is None


def test_running_claim_stays_occupied_after_reopen(tmp_path):
    ledger = make_ledger(tmp_path)
    first, second = [ledger.reserve(**new_request()) for _ in range(2)]
    ledger.claim(first.owner_id, first.id, now_ms=1500)
    assert make_ledger(tmp_path).claim(second.owner_id, second.id, now_ms=1600) is None
