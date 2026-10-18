import asyncio
import hashlib
import json
from dataclasses import replace

import pytest

from app.ai.contextual_chat import ContextualChatWorker, NativeTextRunner
from app.ai.history_contract import HistoryError, encoded
from app.ai.native_text import NativeTextError, TextSessionPolicy
from app.ai.provider_usage import MAX_RESPONSES, UsageReceipt, counts
from app.ai.schemas import ChatResult
from app.ai.server_chat import decrypt_entry
from app.ai.text_supervisor import SupervisorError, SupervisorTextRunner, _read, _write
from test_ai_compact_chat import accepted, settings
from test_ai_contextual_chat import Process
from test_ai_history_contract import NOW
from test_ai_history_store import KEY, OTHER, OWNER, store
from test_ai_native_text import POLICY, transcript
from test_ai_text_supervisor import PEER, request, serving


def usage(**changes):
    return (
        dict(
            totalTokens=120,
            inputTokens=100,
            cachedInputTokens=20,
            cacheWriteInputTokens=0,
            outputTokens=20,
            reasoningOutputTokens=5,
        )
        | changes
    )


def event(*, response="sample-response", counts=None, **changes):
    return dict(
        method="rawResponse/completed",
        params=dict(
            threadId="sample-thread",
            turnId="sample-turn",
            responseId=response,
            usage=usage() if counts is None else counts,
            usageMetadata={"private": "must not be retained"},
        )
        | changes,
    )


def frames(*events):
    result = transcript()
    result[-1:-1] = list(events)
    return result


def stored_entry(value, entry, *, key=KEY):
    with value._locked():
        db = value._open(OWNER, key, create=False)
        if db is None:
            return None
        try:
            row = db.execute(
                "SELECT * FROM server_chat WHERE job_id=?", (entry.id,)
            ).fetchone()
            return None if row is None else decrypt_entry(value, OWNER, key, row)
        finally:
            db.close()


def test_exact_provider_event_reaches_runner_bound_to_the_input():
    runner = NativeTextRunner(Process(frames(event())))

    async def scenario():
        assert await runner.run("Обзор истории", POLICY) == {"text": "Synthetic answer"}
        assert await runner.stop_and_confirm()

    asyncio.run(scenario())
    result = runner.usage_receipt.as_dict()
    assert result["inputDigest"] == hashlib.sha256("Обзор истории".encode()).hexdigest()
    assert (
        result["instructionsDigest"]
        == hashlib.sha256(POLICY.instructions.encode()).hexdigest()
    )
    assert result["model"] == POLICY.model
    assert result["responses"] == [
        dict(
            responseDigest=hashlib.sha256(b"sample-response").hexdigest(), usage=usage()
        )
    ]
    assert "must not be retained" not in json.dumps(result)
    assert "sample-response" not in json.dumps(result)


@pytest.mark.parametrize("field", ["threadId", "turnId"])
def test_usage_cannot_cross_a_thread_or_turn(field):
    runner = NativeTextRunner(Process(frames(event(**{field: "foreign"}))))
    with pytest.raises(NativeTextError, match="text_correlation_rejected"):
        asyncio.run(runner.run("Sample", POLICY))
    assert runner.usage_receipt is None


@pytest.mark.parametrize("bad", [True, -1, 1.5, 2**63, "100"])
def test_invalid_provider_count_is_not_preserved_as_valid_usage(bad):
    runner = NativeTextRunner(Process(frames(event(counts=usage(inputTokens=bad)))))
    with pytest.raises(NativeTextError, match="text_usage_rejected"):
        asyncio.run(runner.run("Sample", POLICY))
    assert runner.usage_receipt is None


def test_identical_duplicate_is_not_double_counted_but_conflict_is_rejected():
    runner = NativeTextRunner(Process(frames(event(), event())))
    asyncio.run(runner.run("Sample", POLICY))
    assert len(runner.usage_receipt.as_dict()["responses"]) == 1
    runner = NativeTextRunner(
        Process(frames(event(), event(counts=usage(totalTokens=121))))
    )
    with pytest.raises(NativeTextError, match="text_usage_rejected"):
        asyncio.run(runner.run("Sample", POLICY))


def test_missing_usage_is_unknown_not_zero_or_a_thread_estimate():
    runner = NativeTextRunner(Process(frames(event(usage=None))))
    asyncio.run(runner.run("Sample", POLICY))
    assert runner.usage_receipt.as_dict()["responses"][0]["usage"] is None
    estimate = dict(
        method="thread/tokenUsage/updated",
        params=dict(
            threadId="sample-thread",
            turnId="sample-turn",
            tokenUsage={
                "total": usage(),
                "last": usage(),
                "modelContextWindow": 500_000,
            },
        ),
    )
    runner = NativeTextRunner(Process(frames(estimate)))
    asyncio.run(runner.run("Sample", POLICY))
    assert runner.usage_receipt is None


def test_supervisor_transports_usage_only_after_confirmed_stop():
    async def scenario():
        process = Process(frames(event()))
        async with serving(lambda: process) as (_, path):
            client = SupervisorTextRunner(path, PEER, POLICY)
            assert await client.run("Sample", POLICY) == {"text": "Synthetic answer"}
            assert process.stops == 1 and await client.stop_and_confirm()
            assert client.usage_receipt.as_dict()["responses"][0]["usage"] == usage()
        async with serving(lambda: Process(frames(event()), stop_receipt=False)) as (
            _,
            path,
        ):
            client = SupervisorTextRunner(path, PEER, POLICY)
            with pytest.raises(SupervisorError):
                await client.run("Sample", POLICY)
            assert client.usage_receipt is None

    asyncio.run(scenario())


def test_legacy_client_receives_legacy_fields_without_usage_opt_in():
    async def scenario():
        async with serving(lambda: Process(frames(event()))) as (_, path):
            reader, writer = await asyncio.open_unix_connection(path)
            await _write(writer, request(), 1024)
            result = await _read(reader, 4096)
            assert result["state"] == "SUCCEEDED" and result["stopped"] is True
            assert set(result) == SupervisorTextRunner.response_fields
            writer.close()
            await writer.wait_closed()

    asyncio.run(scenario())


@pytest.mark.parametrize("revision", [None, True, "foreign-revision"])
def test_unknown_usage_revision_is_rejected_before_process_start(revision):
    async def scenario():
        async with serving(lambda: pytest.fail("must not start")) as (_, path):
            reader, writer = await asyncio.open_unix_connection(path)
            await _write(writer, request(usageReceiptRevision=revision), 1024)
            assert await reader.read() == b""
            writer.close()
            await writer.wait_closed()

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "field,value",
    [
        ("cachedInputTokens", 101),
        ("cacheWriteInputTokens", 101),
        ("reasoningOutputTokens", 21),
        ("totalTokens", 119),
        ("extra", 1),
    ],
)
def test_invalid_counter_relationships_are_rejected(field, value):
    with pytest.raises(ValueError, match="invalid_provider_usage"):
        counts(usage(**{field: value}))


def test_optional_cache_write_counter_matches_pinned_schema_default():
    value = usage()
    del value["cacheWriteInputTokens"]
    assert counts(value) == usage()


def test_response_count_is_bounded_and_not_aggregated():
    runner = NativeTextRunner(
        Process(frames(*[event(response=str(i)) for i in range(MAX_RESPONSES)]))
    )
    asyncio.run(runner.run("Sample", POLICY))
    result = runner.usage_receipt.as_dict()
    assert len(result["responses"]) == MAX_RESPONSES
    assert "totalTokens" not in result
    runner = NativeTextRunner(
        Process(frames(*[event(response=str(i)) for i in range(MAX_RESPONSES + 1)]))
    )
    with pytest.raises(NativeTextError, match="text_usage_rejected"):
        asyncio.run(runner.run("Sample", POLICY))
    assert runner.usage_receipt is None


def receipt_for(policy=POLICY, text="Sample"):
    return UsageReceipt.create(
        policy.model,
        policy.instructions,
        text,
        [dict(responseDigest="a1" * 32, usage=usage())],
    )


@pytest.mark.parametrize(
    "field,value",
    [
        ("model", "foreign-model"),
        ("instructionsDigest", "f1" * 32),
        ("inputDigest", "f2" * 32),
    ],
)
def test_supervisor_rejects_foreign_usage_binding(field, value):
    async def scenario():
        async with serving() as (supervisor, path):
            supervisor._receipt_extra = lambda _: {
                "usageReceipt": receipt_for().as_dict() | {field: value}
            }
            client = SupervisorTextRunner(path, PEER, POLICY)
            with pytest.raises(SupervisorError, match="supervisor_usage_rejected"):
                await client.run("Sample", POLICY)
            assert client.usage_receipt is None
            assert await client.stop_and_confirm()

    asyncio.run(scenario())


@pytest.mark.parametrize("body", [b"{}", b"[]", b"invalid", b"x" * 8193])
def test_malformed_receipt_is_a_fixed_error(body):
    with pytest.raises(ValueError, match="^invalid_provider_usage_receipt$"):
        UsageReceipt(body)


def test_receipt_is_immutable_and_canonical():
    receipt = receipt_for()
    value = receipt.as_dict()
    value["responses"][0]["usage"]["totalTokens"] = 999
    assert receipt.as_dict()["responses"][0]["usage"]["totalTokens"] == 120
    with pytest.raises(ValueError):
        UsageReceipt(receipt.body + b" ")
    value = receipt.as_dict()
    value["responses"].append(value["responses"][0])
    with pytest.raises(ValueError):
        UsageReceipt(encoded(value))


def test_archive_rejects_mismatched_input_and_failed_outcome(tmp_path):
    history = store(tmp_path)
    entry, work = accepted(history)
    prepared = history.prepare_compact_chat(work, settings(), now_ms=NOW)
    policy = TextSessionPolicy(work.policy.model, work.policy.system_prompt)
    final = replace(entry, state="SUCCEEDED", started_ms=NOW, finished_ms=NOW + 1)
    with pytest.raises(HistoryError):
        history.finish_chat(
            final,
            ChatResult("Synthetic answer"),
            now_ms=NOW + 2,
            usage_receipt=receipt_for(policy, "foreign"),
        )
    matching = receipt_for(policy, prepared.input_body.decode())
    with pytest.raises(HistoryError):
        history.finish_chat(
            replace(final, state="FAILED"), None, now_ms=NOW + 2, usage_receipt=matching
        )
    assert "providerUsage" not in stored_entry(history, entry)
    history.finish_chat(
        final, ChatResult("Synthetic answer"), now_ms=NOW + 2, usage_receipt=matching
    )
    history.finish_chat(final, ChatResult("Synthetic answer"), now_ms=NOW + 3)
    assert stored_entry(history, entry)["providerUsage"] == matching.as_dict()
    changed = matching.as_dict()
    changed["responses"][0]["usage"]["totalTokens"] = 121
    with pytest.raises(HistoryError):
        history.finish_chat(
            final,
            ChatResult("Synthetic answer"),
            now_ms=NOW + 4,
            usage_receipt=UsageReceipt(encoded(changed)),
        )


def test_compact_context_usage_survives_encrypted_finalization_without_changing_receipt(
    tmp_path,
):
    history = store(tmp_path)
    entry, work = accepted(history)
    process = Process(frames(event()))
    policy = TextSessionPolicy(work.policy.model, work.policy.system_prompt)

    async def scenario():
        async with serving(lambda: process, policy=policy) as (_, path):
            worker = ContextualChatWorker(
                work,
                history,
                clock_ms=lambda: NOW,
                runner=SupervisorTextRunner(path, PEER, policy),
                compact_context=settings(),
            )
            result = await worker.run()
            assert await worker.stop_and_confirm()
            prepared = history.chat_context_receipt(
                OWNER, KEY, entry.id, now_ms=NOW + 1
            )
            final = replace(
                entry, state="SUCCEEDED", started_ms=NOW, finished_ms=NOW + 2
            )
            history.finish_chat(
                final,
                ChatResult.decode(result),
                now_ms=NOW + 3,
                usage_receipt=worker.usage_receipt,
            )
            assert (
                history.chat_context_receipt(OWNER, KEY, entry.id, now_ms=NOW + 4)
                == prepared
            )
            saved = stored_entry(history, entry)
            assert (
                saved["providerUsage"]["inputDigest"]
                == saved["contextReceipt"]["manifest"]["frameDigest"]
            )
            assert saved["providerUsage"]["responses"][0]["usage"] == usage()
            assert stored_entry(history, entry, key=OTHER) is None
            assert "providerUsage" not in json.dumps(
                history.chat_context(OWNER, KEY, now_ms=NOW + 4)
            )

    asyncio.run(scenario())
