import sqlite3
import threading
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from uuid import uuid4

import pytest

from app.ai.job_ledger import KINDS, LedgerError

from test_ai_job_ledger import make_ledger, new_request


def request(**changes):
    value = new_request(**changes)
    value.pop("kind")
    return value


def admit(ledger, **options):
    method = getattr(ledger, "reserve_and_claim_daily", None)
    assert callable(method), "atomic idle-only daily capacity admission is absent"
    return method(**options)


def test_idle_admission_creates_running_claim_without_waiting_row(tmp_path):
    ledger = make_ledger(tmp_path)
    given = request()
    admission = admit(ledger, **given)
    assert admission.disposition == "ADMITTED"
    assert admission.job == admission.claim.job
    assert admission.job.kind == "DAILY_REVIEW"
    assert admission.job.state == "RUNNING"
    assert admission.job.created_ms == admission.job.started_ms == given["now_ms"]
    assert admission.claim.token
    ledger.engine.dispose()


def test_already_queued_chat_defers_daily_without_blocking_its_owner(tmp_path):
    ledger = make_ledger(tmp_path)
    waiting = ledger.reserve(**new_request())
    background = request()
    admission = admit(ledger, **background)
    assert admission.disposition == "DEFERRED"
    assert admission.job is None and admission.claim is None
    assert ledger.claim(waiting.owner_id, waiting.id, now_ms=1200) is not None
    foreground = ledger.reserve(**new_request(owner_id=background["owner_id"]))
    assert foreground.state == "QUEUED"
    ledger.engine.dispose()


def test_duplicate_running_request_never_reissues_claim_token(tmp_path):
    ledger = make_ledger(tmp_path)
    given = request()
    first = admit(ledger, **given)
    again = admit(ledger, **(given | {"now_ms": 1200}))
    assert again.disposition == "RECORDED" and again.job == first.job
    assert again.claim is None
    assert ledger.claim(first.job.owner_id, first.job.id, now_ms=1300) is None
    assert ledger.claim(str(uuid4()), first.job.id, now_ms=1300) is None
    ledger.engine.dispose()


def rows(tmp_path):
    with sqlite3.connect(tmp_path / "jobs.sqlite") as db:
        return db.execute("SELECT request_id,state,claim_token FROM ai_jobs").fetchall()


@pytest.mark.parametrize("kind", sorted(KINDS))
@pytest.mark.parametrize("state", ["QUEUED", "RUNNING", "CANCEL_REQUESTED"])
def test_any_prior_active_work_defers_without_a_new_reservation(tmp_path, kind, state):
    ledger = make_ledger(tmp_path)
    prior = ledger.reserve(**new_request(kind=kind))
    if state != "QUEUED":
        ledger.claim(prior.owner_id, prior.id, now_ms=1100)
    if state == "CANCEL_REQUESTED":
        ledger.cancel(prior.owner_id, prior.id, now_ms=1150)
    admission = admit(ledger, **request(now_ms=1200))
    assert admission.disposition == "DEFERRED"
    assert admission.job is None and admission.claim is None
    assert len(rows(tmp_path)) == 1
    assert ledger.get(prior.owner_id, prior.id).state == state
    ledger.engine.dispose()


def test_expired_waiting_work_frees_capacity_but_running_work_never_times_out_implicitly(
    tmp_path,
):
    ledger = make_ledger(tmp_path)
    stale = ledger.reserve(**new_request(deadline_ms=1500))
    first = admit(ledger, **request(now_ms=1600, deadline_ms=2000))
    assert first.disposition == "ADMITTED"
    assert ledger.get(stale.owner_id, stale.id).state == "EXPIRED"
    blocked = admit(ledger, **request(now_ms=2200, deadline_ms=3000))
    assert blocked.disposition == "DEFERRED"
    assert ledger.get(first.job.owner_id, first.job.id).state == "RUNNING"
    ledger.engine.dispose()


def test_cancel_requested_holds_capacity_until_stopped_settlement(tmp_path):
    ledger = make_ledger(tmp_path)
    given = request()
    first = admit(ledger, **given)
    job = first.job
    ledger.cancel(job.owner_id, job.id, now_ms=1200)
    assert admit(ledger, **request(now_ms=1300)).disposition == "DEFERRED"
    assert admit(ledger, **(given | {"now_ms": 1300})).claim is None
    settled = ledger.settle_stopped(
        job.owner_id, job.id, first.claim.token, "FAILED", now_ms=1400
    )
    assert settled.state == "CANCELLED"
    assert admit(ledger, **request(now_ms=1500)).disposition == "ADMITTED"
    ledger.engine.dispose()


@pytest.mark.parametrize(
    "state", ["SUCCEEDED", "FAILED", "UNKNOWN", "CANCELLED", "EXPIRED"]
)
def test_terminal_duplicate_is_audit_only_and_fresh_identity_may_start(tmp_path, state):
    ledger = make_ledger(tmp_path)
    given = request()
    first = admit(ledger, **given)
    job, token = first.job, first.claim.token
    now = 1200
    target = state
    if state == "CANCELLED":
        ledger.cancel(job.owner_id, job.id, now_ms=1100)
        target = "FAILED"
    elif state == "EXPIRED":
        now, target = job.deadline_ms, "SUCCEEDED"
    settled = ledger.settle_stopped(job.owner_id, job.id, token, target, now_ms=now)
    assert settled.state == state
    again = admit(ledger, **(given | {"now_ms": now + 1}))
    assert (
        again.disposition == "RECORDED" and again.job == settled and again.claim is None
    )
    assert (
        admit(ledger, **request(now_ms=now + 1, deadline_ms=now + 2000)).disposition
        == "ADMITTED"
    )
    ledger.engine.dispose()


def test_restart_preserves_running_claim_without_reissuing_authority(tmp_path):
    ledger = make_ledger(tmp_path)
    given = request()
    first = admit(ledger, **given)
    ledger.engine.dispose()
    reopened = make_ledger(tmp_path)
    assert reopened.abandon_queued(now_ms=1200) == 0
    again = admit(reopened, **(given | {"now_ms": 1200}))
    assert again.disposition == "RECORDED" and again.claim is None
    assert again.job == first.job
    assert admit(reopened, **request(now_ms=1200)).disposition == "DEFERRED"
    reopened.engine.dispose()


@pytest.mark.parametrize("state", ["QUEUED", "CANCELLED", "EXPIRED", "UNKNOWN"])
def test_legacy_reserved_request_is_never_promoted_or_claimed(tmp_path, state):
    ledger = make_ledger(tmp_path)
    given = request()
    original = ledger.reserve(**given, kind="DAILY_REVIEW")
    now = 1200
    if state == "CANCELLED":
        ledger.cancel(original.owner_id, original.id, now_ms=1100)
    elif state == "EXPIRED":
        now = 11000
    elif state == "UNKNOWN":
        ledger.abandon_queued(now_ms=1100)
    again = admit(ledger, **(given | {"now_ms": now}))
    assert again.disposition == "RECORDED" and again.claim is None
    assert again.job.id == original.id and again.job.state == state
    assert len(rows(tmp_path)) == 1
    ledger.engine.dispose()


@pytest.mark.parametrize(
    "changes",
    [
        {"digest": "b" * 64},
        {"route_revision": "pilot-v2"},
        {"deadline_ms": 9999},
        {"session_id": str(uuid4()), "key_hash": "b" * 64},
    ],
)
def test_duplicate_metadata_conflict_never_recovers_or_replaces_claim(
    tmp_path, changes
):
    ledger = make_ledger(tmp_path)
    given = request()
    first = admit(ledger, **given)
    with pytest.raises(LedgerError, match="request_conflict"):
        admit(ledger, **(given | changes))
    assert ledger.get(first.job.owner_id, first.job.id) == first.job
    assert len(rows(tmp_path)) == 1
    ledger.engine.dispose()


def test_scoped_session_key_binding_is_preserved_and_immutable(tmp_path):
    ledger = make_ledger(tmp_path)
    given = request(session_id=str(uuid4()), key_hash="b" * 64)
    first = admit(ledger, **given)
    assert (
        first.job.session_id == given["session_id"]
        and first.job.key_hash == given["key_hash"]
    )
    assert (
        ledger.get(
            first.job.owner_id, first.job.id, session_id=str(uuid4()), key_hash="b" * 64
        )
        is None
    )
    for changes in ({"session_id": str(uuid4())}, {"key_hash": "c" * 64}):
        with pytest.raises(LedgerError, match="request_conflict"):
            admit(ledger, **(given | changes))
    ledger.engine.dispose()


@pytest.mark.parametrize(
    "changes",
    [
        {"owner_id": "invalid"},
        {"request_id": "invalid"},
        {"digest": "bad"},
        {"digest": None},
        {"route_revision": "../path"},
        {"route_revision": ""},
        {"route_revision": None},
        {"now_ms": True},
        {"now_ms": 0},
        {"deadline_ms": True},
        {"deadline_ms": 0},
        {"deadline_ms": 1000},
        {"deadline_ms": 901001},
        {"max_deadline_ms": 0},
        {"max_deadline_ms": True},
        {"max_deadline_ms": 900001},
        {"session_id": str(uuid4())},
        {"key_hash": "b" * 64},
        {"session_id": "invalid", "key_hash": "b" * 64},
        {"session_id": str(uuid4()), "key_hash": "bad"},
    ],
)
def test_invalid_metadata_cannot_create_capacity(tmp_path, changes):
    ledger = make_ledger(tmp_path)
    with pytest.raises(ValueError):
        admit(ledger, **request(**changes))
    assert rows(tmp_path) == []
    ledger.engine.dispose()


def test_daily_method_cannot_choose_another_kind(tmp_path):
    ledger = make_ledger(tmp_path)
    with pytest.raises(TypeError):
        admit(ledger, **(request() | {"kind": "CHAT"}))
    assert rows(tmp_path) == []
    ledger.engine.dispose()


def test_duplicate_clock_cannot_go_before_recorded_execution_or_settlement(tmp_path):
    ledger = make_ledger(tmp_path)
    given = request()
    first = admit(ledger, **given)
    with pytest.raises(ValueError, match="clock_regression"):
        admit(ledger, **(given | {"now_ms": 999}))
    ledger.settle_stopped(
        first.job.owner_id, first.job.id, first.claim.token, "FAILED", now_ms=1500
    )
    with pytest.raises(ValueError, match="clock_regression"):
        admit(ledger, **(given | {"now_ms": 1400}))
    ledger.engine.dispose()


def test_foreign_owner_cannot_recover_recorded_job_or_claim(tmp_path):
    ledger = make_ledger(tmp_path)
    given = request()
    first = admit(ledger, **given)
    other = given | {"owner_id": str(uuid4())}
    denied = admit(ledger, **other)
    assert (
        denied.disposition == "DEFERRED" and denied.job is None and denied.claim is None
    )
    ledger.settle_stopped(
        first.job.owner_id, first.job.id, first.claim.token, "FAILED", now_ms=1200
    )
    new = admit(ledger, **(other | {"now_ms": 1300}))
    assert new.disposition == "ADMITTED" and new.job.id != first.job.id
    assert new.job.owner_id == other["owner_id"]
    ledger.engine.dispose()


@pytest.mark.parametrize("same_identity", [True, False])
def test_multiple_connections_admit_once_and_never_leave_waiting_daily_rows(
    tmp_path, same_identity
):
    ledgers = [make_ledger(tmp_path) for _ in range(8)]
    base = request()
    requests = [base if same_identity else request() for _ in range(16)]
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = [
            pool.submit(admit, ledgers[i % 8], **given)
            for i, given in enumerate(requests)
        ]
        admissions = [future.result() for future in futures]
    assert sum(a.disposition == "ADMITTED" for a in admissions) == 1
    assert sum(a.claim is not None for a in admissions) == 1
    expected = "RECORDED" if same_identity else "DEFERRED"
    assert sum(a.disposition == expected for a in admissions) == 15
    assert len(rows(tmp_path)) == 1 and rows(tmp_path)[0][1] == "RUNNING"
    for ledger in ledgers:
        ledger.engine.dispose()


@pytest.mark.parametrize("when", ["before_commit", "after_commit"])
def test_commit_failure_never_returns_uncommitted_or_reissued_claim(
    tmp_path, monkeypatch, when
):
    ledger = make_ledger(tmp_path)
    given = request()
    original = ledger._write

    @contextmanager
    def uncertain():
        with original() as db:
            yield db
            if when == "before_commit":
                raise RuntimeError("synthetic interrupted transaction")
        raise RuntimeError("synthetic acknowledgement lost")

    monkeypatch.setattr(ledger, "_write", uncertain)
    with pytest.raises(RuntimeError):
        admit(ledger, **given)
    monkeypatch.undo()
    again = admit(ledger, **given)
    assert again.disposition == ("ADMITTED" if when == "before_commit" else "RECORDED")
    assert (again.claim is not None) == (when == "before_commit")
    assert len(rows(tmp_path)) == 1
    ledger.engine.dispose()


@pytest.mark.parametrize("foreground_first", [True, False])
def test_interactive_race_is_decided_inside_the_metadata_transaction(
    tmp_path, monkeypatch, foreground_first
):
    foreground, background = make_ledger(tmp_path), make_ledger(tmp_path)
    held, release, attempted = threading.Event(), threading.Event(), threading.Event()
    leader, follower = (
        (foreground, background) if foreground_first else (background, foreground)
    )
    lead_write, follow_write = leader._write, follower._write

    @contextmanager
    def gated():
        with lead_write() as db:
            held.set()
            assert release.wait(5)
            yield db

    @contextmanager
    def following():
        attempted.set()
        with follow_write() as db:
            yield db

    monkeypatch.setattr(leader, "_write", gated)
    monkeypatch.setattr(follower, "_write", following)
    fg_request, bg_request = new_request(), request()
    with ThreadPoolExecutor(max_workers=2) as pool:
        try:
            first = (
                pool.submit(foreground.reserve, **fg_request)
                if foreground_first
                else pool.submit(admit, background, **bg_request)
            )
            assert held.wait(5)
            second = (
                pool.submit(admit, background, **bg_request)
                if foreground_first
                else pool.submit(foreground.reserve, **fg_request)
            )
            assert attempted.wait(5)
            release.set()
            a, b = first.result(), second.result()
        finally:
            release.set()
    fg, bg = (a, b) if foreground_first else (b, a)
    assert fg.kind == "CHAT" and fg.state == "QUEUED"
    assert bg.disposition == ("DEFERRED" if foreground_first else "ADMITTED")
    if not foreground_first:
        assert foreground.claim(fg.owner_id, fg.id, now_ms=1200) is None
        background.settle_stopped(
            bg.job.owner_id, bg.job.id, bg.claim.token, "FAILED", now_ms=1300
        )
        assert foreground.claim(fg.owner_id, fg.id, now_ms=1400) is not None
    assert len(rows(tmp_path)) == (1 if foreground_first else 2)
    foreground.engine.dispose()
    background.engine.dispose()


def test_wrong_claim_token_cannot_release_daily_capacity(tmp_path):
    ledger = make_ledger(tmp_path)
    first = admit(ledger, **request())
    with pytest.raises(LedgerError, match="invalid_claim"):
        ledger.settle_stopped(
            first.job.owner_id, first.job.id, str(uuid4()), "FAILED", now_ms=1200
        )
    assert admit(ledger, **request(now_ms=1300)).disposition == "DEFERRED"
    ledger.engine.dispose()


def test_legacy_daily_claim_cannot_bypass_already_waiting_chat(tmp_path):
    ledger = make_ledger(tmp_path)
    background = ledger.reserve(**new_request(kind="DAILY_REVIEW"))
    foreground = ledger.reserve(**new_request())
    assert ledger.claim(background.owner_id, background.id, now_ms=1200) is None
    interactive = ledger.claim(foreground.owner_id, foreground.id, now_ms=1300)
    assert interactive is not None
    ledger.settle_stopped(
        foreground.owner_id, foreground.id, interactive.token, "FAILED", now_ms=1400
    )
    assert ledger.claim(background.owner_id, background.id, now_ms=1500) is not None
    ledger.engine.dispose()


@pytest.mark.parametrize("kind", sorted(KINDS - {"DAILY_REVIEW"}))
def test_legacy_daily_claim_yields_to_every_non_daily_waiter(tmp_path, kind):
    ledger = make_ledger(tmp_path)
    background = ledger.reserve(**new_request(kind="DAILY_REVIEW"))
    foreground = ledger.reserve(**new_request(kind=kind))
    assert ledger.claim(background.owner_id, background.id, now_ms=1200) is None
    assert ledger.claim(foreground.owner_id, foreground.id, now_ms=1300) is not None
    ledger.engine.dispose()


def test_expired_non_daily_waiter_does_not_starve_legacy_daily(tmp_path):
    ledger = make_ledger(tmp_path)
    background = ledger.reserve(**new_request(kind="DAILY_REVIEW"))
    expired = ledger.reserve(**new_request(deadline_ms=1500))
    assert ledger.claim(background.owner_id, background.id, now_ms=1600) is not None
    assert ledger.get(expired.owner_id, expired.id).state == "EXPIRED"
    ledger.engine.dispose()


def test_two_legacy_daily_waiters_do_not_deadlock_each_other(tmp_path):
    ledger = make_ledger(tmp_path)
    first, second = [
        ledger.reserve(**new_request(kind="DAILY_REVIEW")) for _ in range(2)
    ]
    claim = ledger.claim(first.owner_id, first.id, now_ms=1200)
    assert claim is not None
    assert ledger.claim(second.owner_id, second.id, now_ms=1300) is None
    ledger.settle_stopped(first.owner_id, first.id, claim.token, "FAILED", now_ms=1400)
    assert ledger.claim(second.owner_id, second.id, now_ms=1500) is not None
    ledger.engine.dispose()
