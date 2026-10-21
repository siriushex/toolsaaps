import asyncio
import importlib
import importlib.util
import sqlite3
import threading
from types import SimpleNamespace
from uuid import uuid4

import pytest
from sqlalchemy import select, update
from test_ai_activation import NOW, complete, device, service  # noqa: F401
from test_ai_daily_glucose import point
from test_ai_history_store import OWNER
from test_ai_job_transport import MutableClock, key_fingerprint

from app.ai.client_auth import attested_device_keys, sessions, subscription_grants
from app.ai.history_contract import HistoryError, encoded
from app.ai.history_store import HistoryStore

MINUTE = 60_000
AT_FIVE = NOW + 300 * MINUTE
DAY = "2025-01-01"


def module():
    assert importlib.util.find_spec("app.ai.daily_facts_service") is not None, (
        "authenticated nightly facts host is absent"
    )
    return importlib.import_module("app.ai.daily_facts_service")


@pytest.fixture
def prepared(request, tmp_path):
    activation = request.getfixturevalue("service")
    impl, auth, *_ = activation
    request = str(uuid4())
    code = auth.issue_subscription(owner_id=OWNER, duration_days=30, now_ms=NOW)
    challenge = impl.start(code=code, request_id=request, now_ms=NOW + 1)
    key, chain = device(activation, challenge)
    tokens = complete(activation, request, key, chain)
    fingerprint = key_fingerprint(key)
    bound = auth.authenticate_attested(
        tokens.access_token, key_fingerprint=fingerprint, now_ms=NOW + 2
    )
    store = HistoryStore(
        tmp_path / "history", master_key=bytes(range(32)), min_free_bytes=0
    )
    store.set_daily_facts_schedule(
        OWNER,
        fingerprint,
        enabled=True,
        zone_id="UTC",
        now_ms=NOW + 2,
        expected_generation=0,
    )
    return SimpleNamespace(
        auth=auth,
        store=store,
        bound=bound,
        key=key,
        clock=MutableClock(AT_FIVE),
        checks=[],
    )


def add_glucose(value):
    start = NOW - 1440 * MINUTE
    rows = [
        point(i).model_copy(
            update={"eventAtMs": start + i * MINUTE, "observedAtMs": NOW + 2}
        )
        for i in (0, 10)
    ]
    value.store.append(
        OWNER,
        value.bound.key_fingerprint,
        encoded(
            {
                "batchId": str(uuid4()),
                "records": [r.model_dump(mode="json") for r in rows],
            }
        ),
        now_ms=NOW + 2,
    )


def worker(value):
    return module().DailyFactsMaintenance(
        value.auth,
        value.store,
        owner_id=OWNER,
        clock_ms=value.clock,
        storage_check=lambda: value.checks.append(True),
    )


def report(value):
    return value.store.get_daily_glucose_report(
        OWNER,
        value.bound.key_fingerprint,
        report_date=DAY,
        zone_id="UTC",
        now_ms=value.clock(),
    )


def roster(value, **changes):
    method = getattr(value.auth, "list_authorized_attested_sessions", None)
    assert callable(method), "fresh bounded background device roster is absent"
    return method(**({"owner_id": OWNER, "now_ms": value.clock()} | changes))


def test_roster_is_owner_bound_without_access_tokens(prepared):
    value = prepared
    assert roster(value) == (value.bound,)
    assert roster(value, owner_id=str(uuid4())) == ()
    assert not hasattr(roster(value)[0], "access_token")


@pytest.mark.parametrize(
    "kind", ["device", "session", "expired", "future_grant", "foreign_owner"]
)
def test_roster_excludes_revoked_expired_and_inconsistent_bindings(prepared, kind):
    value = prepared
    with value.auth.engine.begin() as db:
        if kind == "device":
            db.execute(update(attested_device_keys).values(revoked_ms=AT_FIVE))
        elif kind == "session":
            db.execute(update(sessions).values(revoked_ms=AT_FIVE))
        elif kind == "expired":
            db.execute(update(subscription_grants).values(expires_ms=AT_FIVE))
        elif kind == "future_grant":
            db.execute(update(subscription_grants).values(activated_ms=AT_FIVE + 1))
        else:
            db.execute(update(sessions).values(owner_id=str(uuid4())))
    assert roster(value) == ()


def test_missing_or_bad_consent_never_creates_another_store(prepared):
    value = prepared
    module()
    disabled = value.store.set_daily_facts_schedule(
        OWNER,
        value.bound.key_fingerprint,
        enabled=False,
        zone_id="UTC",
        now_ms=NOW + 3,
        expected_generation=1,
    )
    asyncio.run(worker(value).tick())
    assert report(value) is None
    assert (
        value.store.get_daily_facts_schedule(
            OWNER, value.bound.key_fingerprint, now_ms=AT_FIVE
        )
        == disabled
    )


def test_before_five_then_one_facts_commit_without_queue_or_model(prepared):
    value = prepared
    add_glucose(value)
    host = worker(value)
    value.clock.value = AT_FIVE - 1
    asyncio.run(host.tick())
    assert report(value) is None
    value.clock.value = AT_FIVE
    asyncio.run(host.tick())
    saved = report(value)
    assert saved is not None and saved.modelState == "NOT_REQUESTED"
    assert saved.facts.current.coveredMinutes == 10.0
    value.clock.value += MINUTE
    asyncio.run(host.tick())
    assert report(value) == saved
    path = value.store.root / (
        value.store._identity(OWNER, value.bound.key_fingerprint) + ".sqlite"
    )
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT count(*) FROM daily_reports").fetchone()[0] == 1
        assert db.execute("SELECT count(*) FROM daily_review_queue").fetchone()[0] == 0
    assert value.checks


def test_completed_day_is_not_rescanned_but_restart_reuses_archive(
    prepared, monkeypatch
):
    value = prepared
    add_glucose(value)
    host = worker(value)
    calls = []
    original = value.store.produce_scheduled_daily_facts

    def produce(*args, **kwargs):
        calls.append(True)
        return original(*args, **kwargs)

    monkeypatch.setattr(value.store, "produce_scheduled_daily_facts", produce)
    asyncio.run(host.tick())
    saved = report(value)
    value.clock.value += 60 * MINUTE
    asyncio.run(host.tick())
    assert len(calls) == 1
    asyncio.run(worker(value).tick())
    assert len(calls) == 2 and report(value) == saved


def test_missing_data_backoff_and_attempt_cap_are_bounded(prepared, monkeypatch):
    value = prepared
    monkeypatch.setattr(module(), "MAX_ATTEMPTS", 2)
    host = worker(value)
    calls = []
    original = value.store.produce_scheduled_daily_facts

    def produce(*args, **kwargs):
        calls.append(value.clock())
        return original(*args, **kwargs)

    monkeypatch.setattr(value.store, "produce_scheduled_daily_facts", produce)
    asyncio.run(host.tick())
    asyncio.run(host.tick())
    assert len(calls) == 1
    value.clock.value += MINUTE
    asyncio.run(host.tick())
    assert len(calls) == 2
    value.clock.value += 60 * MINUTE
    asyncio.run(host.tick())
    assert len(calls) == 2 and report(value) is None


def test_revocation_between_roster_and_work_prevents_source_access(
    prepared, monkeypatch
):
    value = prepared
    module()
    original = value.auth.list_authorized_attested_sessions

    def revoke(**kwargs):
        found = original(**kwargs)
        value.auth.revoke_attested_device(
            owner_id=OWNER,
            key_fingerprint=value.bound.key_fingerprint,
            now_ms=value.clock(),
        )
        return found

    monkeypatch.setattr(value.auth, "list_authorized_attested_sessions", revoke)
    monkeypatch.setattr(
        value.store,
        "get_daily_facts_schedule",
        lambda *a, **kw: pytest.fail("revoked source access"),
    )
    asyncio.run(worker(value).tick())


def test_clock_reversal_does_not_reset_completed_day(prepared):
    value = prepared
    add_glucose(value)
    host = worker(value)
    asyncio.run(host.tick())
    saved = report(value)
    value.clock.value -= 1
    with pytest.raises(ValueError, match="daily_facts_clock_regression"):
        asyncio.run(host.tick())
    value.clock.value += MINUTE
    asyncio.run(host.tick())
    assert report(value) == saved


def test_cancellation_drains_source_thread_and_refuses_overlapping_tick(
    prepared, monkeypatch
):
    value = prepared
    add_glucose(value)
    host = worker(value)
    entered, release = threading.Event(), threading.Event()
    original = value.store.produce_scheduled_daily_facts

    def blocked(*args, **kwargs):
        entered.set()
        assert release.wait(2)
        return original(*args, **kwargs)

    monkeypatch.setattr(value.store, "produce_scheduled_daily_facts", blocked)

    async def scenario():
        task = asyncio.create_task(host.tick())
        assert await asyncio.to_thread(entered.wait, 1)
        with pytest.raises(RuntimeError, match="daily_facts_busy"):
            await host.tick()
        task.cancel()
        await asyncio.sleep(0)
        assert not task.done()
        release.set()
        with pytest.raises(asyncio.CancelledError):
            await task

    try:
        asyncio.run(scenario())
    finally:
        release.set()
    assert report(value) is not None


def test_stop_does_not_begin_device_work(prepared, monkeypatch):
    value = prepared
    host = worker(value)
    monkeypatch.setattr(
        value.auth,
        "list_authorized_attested_sessions",
        lambda **kw: pytest.fail("stopped roster"),
    )

    async def scenario():
        stop = asyncio.Event()
        stop.set()
        await host.run(stop)

    asyncio.run(scenario())


def test_roster_overflow_refuses_partial_membership(prepared):
    value = prepared
    module()
    with value.auth.engine.begin() as db:
        session = dict(db.execute(select(sessions)).mappings().one())
        grant = dict(db.execute(select(subscription_grants)).mappings().one())
        bound = dict(db.execute(select(attested_device_keys)).mappings().one())
        for i in range(64):
            identity = str(uuid4())
            db.execute(sessions.insert().values(session | {"id": identity}))
            db.execute(
                subscription_grants.insert().values(
                    grant
                    | {
                        "session_id": identity,
                        "code_digest": f"{i:064x}",
                    }
                )
            )
            db.execute(
                attested_device_keys.insert().values(
                    bound
                    | {
                        "session_id": identity,
                        "key_hash": f"{i:064x}",
                    }
                )
            )
    with pytest.raises(ValueError, match="background_device_limit"):
        roster(value)


def test_data_later_arriving_is_used_only_after_retry_pause(prepared):
    value = prepared
    host = worker(value)
    asyncio.run(host.tick())
    add_glucose(value)
    value.clock.value += MINUTE - 1
    asyncio.run(host.tick())
    assert report(value) is None
    value.clock.value += 1
    asyncio.run(host.tick())
    assert report(value).facts.current.coveredMinutes == 10.0


def test_failed_source_read_is_retried_without_false_success(prepared, monkeypatch):
    value = prepared
    add_glucose(value)
    host = worker(value)
    original = value.store.produce_scheduled_daily_facts
    calls = []

    def broken(*args, **kwargs):
        calls.append(True)
        raise HistoryError(503, "daily_glucose_unavailable")

    monkeypatch.setattr(value.store, "produce_scheduled_daily_facts", broken)
    asyncio.run(host.tick())
    assert host.last_failure == "FACTS_UNAVAILABLE" and report(value) is None
    monkeypatch.setattr(value.store, "produce_scheduled_daily_facts", original)
    asyncio.run(host.tick())
    assert report(value) is None
    value.clock.value += MINUTE
    asyncio.run(host.tick())
    assert report(value) is not None and len(calls) == 1


def test_revocation_during_admitted_facts_does_not_publish_or_restart(
    prepared, monkeypatch
):
    value = prepared
    add_glucose(value)
    host = worker(value)
    original = value.store.produce_scheduled_daily_facts
    calls = []

    def revoke_after_work(*args, **kwargs):
        result = original(*args, **kwargs)
        calls.append(True)
        value.auth.revoke_attested_device(
            owner_id=OWNER,
            key_fingerprint=value.bound.key_fingerprint,
            now_ms=value.clock(),
        )
        return result

    monkeypatch.setattr(value.store, "produce_scheduled_daily_facts", revoke_after_work)
    asyncio.run(host.tick())
    value.clock.value += MINUTE
    asyncio.run(host.tick())
    assert len(calls) == 1 and not host._attempts
    assert report(value).modelState == "NOT_REQUESTED"


def test_optin_change_and_new_day_are_not_hidden_by_completion_cache(prepared):
    value = prepared
    add_glucose(value)
    host = worker(value)
    asyncio.run(host.tick())
    value.clock.value += 1
    value.store.set_daily_facts_schedule(
        OWNER,
        value.bound.key_fingerprint,
        enabled=True,
        zone_id="Asia/Tbilisi",
        now_ms=value.clock(),
        expected_generation=1,
    )
    asyncio.run(host.tick())
    other = value.store.get_daily_glucose_report(
        OWNER,
        value.bound.key_fingerprint,
        report_date=DAY,
        zone_id="Asia/Tbilisi",
        now_ms=value.clock(),
    )
    assert other is not None
    value.clock.value += 1440 * MINUTE
    asyncio.run(host.tick())
    assert next(iter(host._attempts.values())).day == "2025-01-02"
    assert next(iter(host._attempts.values())).complete is False


def test_bad_schedule_has_bounded_read_backoff(prepared, monkeypatch):
    value = prepared
    host = worker(value)
    calls = []

    def broken(*args, **kwargs):
        calls.append(True)
        raise HistoryError(503, "history_storage_unavailable")

    monkeypatch.setattr(value.store, "get_daily_facts_schedule", broken)
    asyncio.run(host.tick())
    assert host.last_failure == "SCHEDULE_UNAVAILABLE"
    value.clock.value += MINUTE
    asyncio.run(host.tick())
    assert len(calls) == 1
    value.clock.value += 30 * MINUTE
    asyncio.run(host.tick())
    assert len(calls) == 2
