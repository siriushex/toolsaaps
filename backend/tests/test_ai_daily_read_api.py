import asyncio
import importlib
import importlib.util
import inspect
import sqlite3
from datetime import UTC, datetime
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.ai import daily_results, daily_worker
from app.ai.daily_glucose import AnalysisRange
from app.ai.history_contract import HistoryError, encoded
from app.ai.history_service import HistoryService
from app.ai.history_store import HistoryStore
from app.ai.job_executor import JobExecutor
from app.ai.job_ledger import JobLedger
from app.ai_server import create_bound_ai_app
from test_ai_activation import NOW as AUTH_NOW, service, signed  # noqa: F401
from test_ai_checked_daily_review import update_source
from test_ai_daily_glucose import MINUTE, NOW, START, point
from test_ai_daily_reports import review, save
from test_ai_daily_worker import Runner, setup
from test_ai_glucose_comparison import CONTEXT
from test_ai_history_store import KEY, OTHER, OWNER
from test_ai_history_transport import signed_post
from test_ai_job_transport import MutableClock, activate, key_fingerprint, proof_headers
from test_ai_native_text import MODEL

PAGE = {"afterSequence": 0, "snapshotSequence": None}


def module():
    assert importlib.util.find_spec("app.ai.server_daily_query") is not None, (
        "bounded daily read projection is absent"
    )
    return importlib.import_module("app.ai.server_daily_query")


def archived(tmp_path):
    parts = setup(tmp_path)
    saved = asyncio.run(daily_results.execute_and_archive(parts[-1], parts[-2])).record
    parts[4].engine.dispose()
    return parts, saved


def test_selected_projection_preserves_meaning_and_excludes_execution_input(tmp_path):
    parts, saved = archived(tmp_path)
    history, _, _, _, _, job, runner, *_ = parts
    before = next(history.root.glob("*.sqlite")).read_bytes()
    result = module().read_result(history, OWNER, KEY, encoded({"jobId": job.id}), now_ms=NOW)
    assert set(result) == {"revision", "jobId", "reportDate", "zoneId", "reportRevision",
        "model", "storedAtMs", "expiresAtMs", "currentState", "sourceCheck", "sourceAtReply", "review"}
    assert result["revision"] == "daily-review-read-v1"
    assert result["currentState"] == "SOURCE_MATCHED"
    assert result["review"] == saved.review.model_dump(mode="json")
    assert result["sourceAtReply"] == saved.sourceAtReply.model_dump(mode="json")
    assert result["sourceCheck"]["status"] == "MATCHED"
    assert result["review"]["publicationState"] == "NOT_ADMITTED"
    assert result["review"]["textMeaning"] == "UNREVIEWED_AI_INTERPRETATION"
    assert result["review"]["validation"] == "REFERENCES_ONLY"
    assert result["review"]["scope"] == "GLUCOSE_ONLY"
    page = module().read_results(history, OWNER, KEY, encoded(PAGE), now_ms=NOW)
    assert page["membershipMeaning"] == "RETAINED_ARCHIVE_ONLY"
    assert page["records"][0]["sourceCheck"] == "NOT_CHECKED"
    assert page["records"][0]["publicationState"] == "NOT_ADMITTED"
    assert runner.calls == runner.stops == 1
    assert next(history.root.glob("*.sqlite")).read_bytes() == before


@pytest.mark.parametrize("change", ["late", "revision", "delete", "new_observation", "superseded"])
def test_selected_current_source_does_not_rewrite_reply(tmp_path, change):
    parts, saved = archived(tmp_path)
    history, rows, _, _, _, job, runner, *_ = parts
    update_source(history, rows, "revision" if change == "superseded" else change)
    if change == "superseded":
        save(history, review(history, now_ms=NOW + MINUTE), now=NOW + MINUTE)
    result = module().read_result(history, OWNER, KEY, encoded({"jobId": job.id}), now_ms=NOW + MINUTE)
    assert result["currentState"] == ("SUPERSEDED" if change == "superseded" else "SOURCE_CHANGED")
    assert result["sourceAtReply"] == saved.sourceAtReply.model_dump(mode="json")
    assert result["review"] == saved.review.model_dump(mode="json")
    assert runner.calls == runner.stops == 1


@pytest.mark.parametrize("body", [b"{}", b"[]", b"null", b"\xff",
    b'{"afterSequence":0,"afterSequence":1,"snapshotSequence":null}',
    encoded(PAGE | {"owner": OWNER}), encoded(PAGE | {"limit": 21}), b" " * 1025,
    *[encoded(PAGE | {"afterSequence": value}) for value in (True, -1, 1.5, "0", 2**63 - 1)],
    *[encoded(PAGE | {"snapshotSequence": value}) for value in (False, -1, 1.5, "1", 2**63 - 1)],
    encoded({"afterSequence": 2, "snapshotSequence": 1})])
def test_invalid_list_query_rejected_before_storage(body, monkeypatch):
    def forbidden(*args, **kwargs):
        pytest.fail("invalid query reached storage")
    monkeypatch.setattr(daily_results, "list_results", forbidden)
    with pytest.raises(HistoryError, match="invalid_daily_read_request") as caught:
        module().read_results(None, OWNER, KEY, body, now_ms=NOW)
    assert caught.value.status == 400


@pytest.mark.parametrize("body", [b"{}", b"[]", b'{"jobId":null}',
    encoded({"jobId": 1}), encoded({"jobId": "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"}),
    encoded({"jobId": "{" + str(uuid4()) + "}"}),
    encoded({"jobId": str(uuid4()), "device": OTHER}),
    b'{"jobId":"a","jobId":"b"}', b" " * 1025])
def test_invalid_selected_query_rejected_before_storage(body, monkeypatch):
    monkeypatch.setattr(daily_results, "get_result", lambda *a, **k: pytest.fail("storage read"))
    with pytest.raises(HistoryError, match="invalid_daily_read_request") as caught:
        module().read_result(None, OWNER, KEY, body, now_ms=NOW)
    assert caught.value.status == 400


def test_missing_expired_and_other_identity_are_indistinguishable(tmp_path):
    parts, saved = archived(tmp_path)
    history, _, _, _, _, job, *_ = parts
    for owner, device, job_id, now in ((OWNER, KEY, str(uuid4()), NOW),
            (OWNER, OTHER, job.id, NOW), (str(uuid4()), KEY, job.id, NOW),
            (OWNER, KEY, job.id, saved.expiresAtMs)):
        with pytest.raises(HistoryError, match="daily_review_not_found") as caught:
            module().read_result(history, owner, device, encoded({"jobId": job_id}), now_ms=now)
        assert caught.value.status == 404


def test_response_limit_refuses_whole_projection(tmp_path, monkeypatch):
    parts, saved = archived(tmp_path)
    mod = module()
    monkeypatch.setattr(mod, "MAX_RESPONSE_BYTES", 32)
    for function, body in ((mod.read_results, PAGE), (mod.read_result, {"jobId": saved.jobId})):
        with pytest.raises(HistoryError, match="daily_review_read_unavailable") as caught:
            function(parts[0], OWNER, KEY, encoded(body), now_ms=NOW)
        assert caught.value.status == 503


def test_list_is_fixed_bounded_and_does_not_recheck_each_source(tmp_path, monkeypatch):
    parts, saved = archived(tmp_path)
    history = parts[0]
    with sqlite3.connect(next(history.root.glob("*.sqlite"))) as db:
        for sequence in range(2, 23):
            record = saved.model_copy(update=dict(sequence=sequence, jobId=str(uuid4()),
                queue=saved.queue.model_copy(update=dict(queueId=str(uuid4())))))
            values = daily_results._metadata(record)
            nonce = bytes([sequence]) * 12
            values.update(nonce=nonce, ciphertext=history._cipher(OWNER, KEY).encrypt(nonce,
                encoded(record.model_dump(mode="json")), history._aad(OWNER, KEY,
                    daily_results.PURPOSE, [values[k] for k in daily_results.META])))
            db.execute("INSERT INTO daily_review_results(" + ",".join(values) + ") VALUES("
                + ",".join("?" for _ in values) + ")", tuple(values.values()))
    monkeypatch.setattr(daily_results, "get_result", lambda *a, **k: pytest.fail("per-row source replay"))
    first = module().read_results(history, OWNER, KEY, encoded(PAGE | {"snapshotSequence": 21}), now_ms=NOW)
    assert len(first["records"]) == 20 and first["hasMore"] is True
    second = module().read_results(history, OWNER, KEY, encoded(dict(
        afterSequence=first["nextAfterSequence"], snapshotSequence=first["snapshotSequence"])), now_ms=NOW)
    assert [row["sequence"] for row in second["records"]] == [21]
    assert second["hasMore"] is False and second["nextAfterSequence"] is None
    assert all(row["sourceCheck"] == "NOT_CHECKED" for row in first["records"])


def test_reads_preserve_source_unavailability_and_storage_errors(tmp_path):
    parts, saved = archived(tmp_path)
    history = parts[0]
    body = encoded({"jobId": saved.jobId})
    with pytest.raises(HistoryError, match="daily_result_clock_regression"):
        module().read_result(history, OWNER, KEY, body, now_ms=NOW - 1)
    with pytest.raises(HistoryError, match="daily_result_snapshot_unavailable"):
        module().read_results(history, OWNER, KEY, encoded(PAGE | {"snapshotSequence": 2}), now_ms=NOW)
    with sqlite3.connect(next(history.root.glob("*.sqlite"))) as db:
        db.execute("DELETE FROM daily_reports")
    result = module().read_result(history, OWNER, KEY, body, now_ms=NOW)
    assert result["currentState"] == "SOURCE_UNAVAILABLE" and result["sourceCheck"] is None
    assert result["sourceAtReply"]["status"] == "MATCHED"


def test_empty_reads_do_not_create_a_device_store(tmp_path):
    history = HistoryStore(tmp_path / "history", master_key=bytes(range(32)), min_free_bytes=0)
    assert module().read_results(history, OWNER, KEY, encoded(PAGE), now_ms=NOW)["records"] == []
    with pytest.raises(HistoryError, match="daily_review_not_found"):
        module().read_result(history, OWNER, KEY, encoded({"jobId": str(uuid4())}), now_ms=NOW)
    assert not list(history.root.glob("*.sqlite"))


def seed_archive(store, owner, device, tmp_path, now):
    # Translate shared synthetic observations to the activation fixture's clock.
    end = now // (1440 * MINUTE) * (1440 * MINUTE)
    start = end - 1440 * MINUTE
    day = datetime.fromtimestamp(start / 1000, UTC).date().isoformat()
    rows = [point(-offset * 1440 + minute, value=3.0, context=CONTEXT)
        for offset in range(3) for minute in range(0, 121, 15)]
    rows = [row.model_copy(update={"eventAtMs": row.eventAtMs - START + start,
        "observedAtMs": now}) for row in rows]
    store.append(owner, device, encoded({"batchId": str(uuid4()),
        "records": [row.model_dump(exclude_unset=True) for row in rows]}), now_ms=now)
    ranges = (AnalysisRange(startMs=start - 21 * 1440 * MINUTE, endMs=end,
        lowMmol=4.0, highMmol=8.0, version="range-1"),)
    facts = store.daily_glucose_review(owner, device, report_date=day, zone_id="UTC", ranges=ranges, now_ms=now)
    store.save_daily_glucose_report(owner, device, facts, now_ms=now)
    entry = store.enqueue_daily_glucose_review(owner, device, report_date=day, zone_id="UTC", now_ms=now)
    given = store.handoff_daily_glucose_review(owner, device, entry.queueId, now_ms=now)
    deadline = now + 5 * MINUTE
    ledger = JobLedger(tmp_path / "daily-jobs.sqlite")
    try:
        job = ledger.reserve(owner_id=owner, request_id=given.request_id, kind="DAILY_REVIEW",
            digest=daily_worker.daily_request_digest(given.work, request_id=given.request_id,
                model=MODEL, deadline_ms=deadline), route_revision=daily_worker.ROUTE_REVISION,
            now_ms=now, deadline_ms=deadline)
        runner = Runner(given.work)
        worker = daily_worker.DailyReviewWorker(job, given, store, device=device,
            model=MODEL, clock_ms=lambda: now, runner=runner)
        result = asyncio.run(daily_results.execute_and_archive(JobExecutor(ledger, clock_ms=lambda: now), worker))
        assert result.archive_state == "STORED"
        return result.record, runner
    finally:
        ledger.engine.dispose()


@pytest.fixture
def transport(service, tmp_path):  # noqa: F811 - imported synthetic pytest fixture
    assert "daily_reads" in inspect.signature(create_bound_ai_app).parameters, "explicit daily read opt-in absent"
    owner = str(uuid4())
    store = HistoryStore(tmp_path / "history", master_key=bytes(range(32)), min_free_bytes=0)
    history = HistoryService(service[1], service[0].proofs, store, owner_id=owner)
    clock = MutableClock()
    with TestClient(create_bound_ai_app(service[0], history=history, daily_reads=True, clock_ms=clock),
            raise_server_exceptions=False) as client:
        client.audit_clock = clock
        _, key, tokens = activate(client, service, owner_id=owner)
        saved, runner = seed_archive(store, owner, key_fingerprint(key), tmp_path, clock())
        yield client, store, owner, key, tokens, saved, runner


def test_signed_reads_are_complete_private_and_do_not_enable_inference(transport):
    client, _, _, key, tokens, saved, runner = transport
    page = signed_post(client, key, tokens, "daily-results", PAGE)
    assert page.status_code == 200 and page.headers["cache-control"] == "no-store"
    assert page.json()["records"][0]["jobId"] == saved.jobId
    result = signed_post(client, key, tokens, "daily-result", {"jobId": saved.jobId})
    assert result.status_code == 200 and result.headers["cache-control"] == "no-store"
    assert result.json()["review"] == saved.review.model_dump(mode="json")
    assert runner.calls == runner.stops == 1
    assert client.get("/api/ai/v1/capabilities").status_code == 404
    assert client.post("/api/ai/v1/jobs", json={}).status_code == 404


def test_read_routes_are_off_by_default_and_require_history(service, tmp_path):  # noqa: F811
    assert "daily_reads" in inspect.signature(create_bound_ai_app).parameters, "explicit daily read opt-in absent"
    history = HistoryService(service[1], service[0].proofs,
        HistoryStore(tmp_path / "history", master_key=bytes(range(32))), owner_id=str(uuid4()))
    with TestClient(create_bound_ai_app(service[0], history=history)) as client:
        for route in ("daily-results", "daily-result"):
            assert client.post("/api/ai/v1/history/" + route, json={}).status_code == 404
    for options in ({"daily_reads": True}, {"daily_reads": 1, "history": history}):
        with pytest.raises(ValueError):
            create_bound_ai_app(service[0], **options)


def test_proof_replay_body_path_and_revocation_fail_before_store(transport, service, monkeypatch):  # noqa: F811
    client, store, _, key, tokens, *_ = transport
    body = encoded(PAGE)
    path = "/api/ai/v1/history/daily-results"
    headers = {"Content-Type": "application/json"} | proof_headers(
        signed(key, path=path, body=body, credential=tokens["access_token"]), key,
        access_token=tokens["access_token"])
    assert client.post(path, content=body, headers=headers).status_code == 200
    monkeypatch.setattr(store, "_daily_read_snapshot", lambda *a, **k: pytest.fail("unauthorized storage read"))
    assert client.post(path, content=body, headers=headers).status_code == 401
    assert signed_post(client, key, tokens, "daily-results", PAGE, proof_body=b"{}").status_code == 401
    assert client.post(path, content=body, headers={"Content-Type": "application/json"}).status_code == 401
    selected_body = encoded({"jobId": str(uuid4())})
    wrong_path_headers = {"Content-Type": "application/json"} | proof_headers(
        signed(key, path=path, body=selected_body, credential=tokens["access_token"]), key,
        access_token=tokens["access_token"])
    assert client.post(path.replace("daily-results", "daily-result"),
        content=selected_body, headers=wrong_path_headers).status_code == 401
    service[1].revoke(tokens["refresh_token"], now_ms=AUTH_NOW + 2)
    assert signed_post(client, key, tokens, "daily-results", PAGE).status_code == 401


def test_signed_read_device_and_owner_isolation(transport, service):  # noqa: F811
    client, _, owner, _, _, saved, _ = transport
    _, other_key, other_tokens = activate(client, service, owner_id=owner)
    assert signed_post(client, other_key, other_tokens, "daily-results", PAGE).json()["records"] == []
    result = signed_post(client, other_key, other_tokens, "daily-result", {"jobId": saved.jobId})
    assert result.status_code == 404 and result.json()["error"] == "daily_review_not_found"
    _, foreign_key, foreign_tokens = activate(client, service)
    assert signed_post(client, foreign_key, foreign_tokens, "daily-results", PAGE).status_code == 401


@pytest.mark.parametrize("refusal", ["revocation", "expiry"])
def test_revocation_during_proof_and_expiry_block_read(transport, service, monkeypatch, refusal):  # noqa: F811
    client, store, owner, key, tokens, *_ = transport
    verify = service[0].proofs.verify
    def revoke_then_verify(**values):
        result = verify(**values)
        service[1].revoke_owner(owner_id=owner, now_ms=AUTH_NOW + 2)
        return result
    if refusal == "revocation":
        monkeypatch.setattr(service[0].proofs, "verify", revoke_then_verify)
    else:
        client.audit_clock.value += 31 * 1440 * MINUTE
    monkeypatch.setattr(store, "_daily_read_snapshot", lambda *a, **k: pytest.fail("revoked storage read"))
    assert signed_post(client, key, tokens, "daily-results", PAGE).status_code == 401


def test_bounds_duplicate_keys_and_storage_failure_do_not_echo(transport, monkeypatch):
    client, store, _, key, tokens, *_ = transport
    assert signed_post(client, key, tokens, "daily-results", {}, raw=b"x" * 1025).status_code == 413
    assert signed_post(client, key, tokens, "daily-results", PAGE,
        raw=b'{"afterSequence":0,"afterSequence":1,"snapshotSequence":null}').status_code == 400
    body, path = encoded(PAGE), "/api/ai/v1/history/daily-results"
    headers = {"Content-Type": "application/json"} | proof_headers(
        signed(key, path=path, body=body, credential=tokens["access_token"]), key,
        access_token=tokens["access_token"])
    assert client.post(path + "?extra=1", content=body, headers=headers).status_code == 400
    def fail(*args, **kwargs):
        raise OSError("private synthetic storage path")
    monkeypatch.setattr(store, "_daily_read_snapshot", fail)
    result = signed_post(client, key, tokens, "daily-results", PAGE)
    assert result.status_code == 503 and "private synthetic" not in result.text
