import hashlib
import json
from dataclasses import replace

import pytest

from app.ai.compact_context import TokenBudget
from app.ai.history_contract import HistoryError
from test_ai_compact_chat import accepted, module, settings
from test_ai_compact_context import prepare
from test_ai_history_contract import NOW
from test_ai_history_store import store
from test_ai_native_text import MODEL


def bound_budget(**changes):
    policy = module().compact_chat_policy(MODEL)
    return TokenBudget(
        **(
            dict(
                window_tokens=500_000,
                reserved_tokens=100_000,
                revision="synthetic-byte-counter",
                counter=len,
                model=policy.model,
                instructions_digest=hashlib.sha256(
                    policy.system_prompt.encode()
                ).hexdigest(),
            )
            | changes
        )
    )


def test_counter_receipt_does_not_claim_model_verification():
    budget = TokenBudget(20_000, 10_000, "synthetic-byte-counter", len)
    prepared = prepare(token_budget=budget)
    manifest = json.loads(prepared.manifest_body)
    assert manifest["tokenStatus"] == "COUNTED_BY_SUPPLIED_COUNTER"
    assert manifest["tokenBudget"] == dict(
        windowTokens=20_000,
        reservedTokens=10_000,
        totalTokens=len(prepared.input_body) + 10_000,
        remainingTokens=10_000 - len(prepared.input_body),
        model=None,
        instructionsDigest=None,
    )
    assert manifest["inputTokens"] == len(prepared.input_body)


def test_absent_counter_has_no_accounting_claim():
    manifest = json.loads(prepare().manifest_body)
    assert manifest["tokenStatus"] == "UNVERIFIED"
    assert manifest["tokenBudget"] is None and manifest["inputTokens"] is None


@pytest.mark.parametrize("count", [0, -1, True, 1.5, None, 10_000_001])
def test_invalid_counter_result_cannot_admit_nonempty_input(count):
    budget = TokenBudget(20_000, 10_000, "synthetic-invalid-count", lambda _: count)
    with pytest.raises(ValueError, match="^compact_token_accounting_failed$"):
        prepare(token_budget=budget)


@pytest.mark.parametrize(
    "changes",
    [
        dict(model=None),
        dict(instructions_digest=None),
        dict(model=""),
        dict(model="invalid model"),
        dict(instructions_digest="not-a-digest"),
    ],
)
def test_partial_or_malformed_budget_binding_is_rejected(changes):
    with pytest.raises(ValueError, match="^invalid_compact_chat_settings$"):
        settings(token_budget=bound_budget(**changes))


def test_chat_refuses_unbound_budget():
    with pytest.raises(ValueError, match="^invalid_compact_chat_settings$"):
        settings(token_budget=TokenBudget(20_000, 10_000, "synthetic-counter", len))


@pytest.mark.parametrize(
    "changes",
    [dict(model="different-model"), dict(instructions_digest="0" * 64)],
)
def test_model_or_instructions_mismatch_precedes_archive_access(
    tmp_path, monkeypatch, changes
):
    value = store(tmp_path)
    _, work = accepted(value)
    options = settings(token_budget=bound_budget(**changes))

    def unexpected_read(*args, **kwargs):
        pytest.fail("budget mismatch reached the archive")

    monkeypatch.setattr(value, "_open", unexpected_read)
    with pytest.raises(HistoryError, match="^compact_chat_budget_binding$"):
        value.prepare_compact_chat(work, options, now_ms=NOW)


@pytest.mark.parametrize("extra_reserve", [-1, 0])
def test_instructions_cannot_consume_native_output_reserve(tmp_path, extra_reserve):
    value = store(tmp_path)
    entry, work = accepted(value)
    reserved = len(work.policy.system_prompt.encode()) + extra_reserve
    options = settings(token_budget=bound_budget(reserved_tokens=reserved))
    with pytest.raises(HistoryError, match="^compact_chat_instruction_budget$"):
        value.prepare_compact_chat(work, options, now_ms=NOW)
    assert (
        value.chat_context_receipt(
            work.identity.owner_id,
            work.identity.key_fingerprint,
            entry.id,
            now_ms=NOW + 1,
        )
        is None
    )


def test_counted_instructions_and_remaining_reserve_are_archived(tmp_path):
    value = store(tmp_path)
    entry, work = accepted(value)
    calls = []

    def counter(body):
        calls.append(body)
        return len(body)

    budget = bound_budget(counter=counter)
    prepared = value.prepare_compact_chat(
        work, settings(token_budget=budget), now_ms=NOW
    )
    assert calls == [work.policy.system_prompt.encode(), prepared.input_body]
    saved = json.loads(
        value.chat_context_receipt(
            work.identity.owner_id,
            work.identity.key_fingerprint,
            entry.id,
            now_ms=NOW + 1,
        )
    )
    assert saved["tokenReserve"] == dict(
        instructionTokens=len(calls[0]),
        remainingReservedTokens=budget.reserved_tokens - len(calls[0]),
        meaning="NATIVE_FRAMING_AND_REASONING_OUTPUT_NOT_INDEPENDENTLY_VERIFIED",
    )
    assert saved["manifest"]["tokenBudget"]["model"] == work.policy.model
    assert (
        saved["manifest"]["tokenBudget"]["instructionsDigest"]
        == budget.instructions_digest
    )
    assert saved["manifest"]["tokenStatus"] == "COUNTED_BY_SUPPLIED_COUNTER"


def test_instruction_counter_error_is_redacted_and_leaves_no_receipt(tmp_path):
    value = store(tmp_path)
    entry, work = accepted(value)

    def counter(_):
        raise RuntimeError("private supplied material")

    with pytest.raises(HistoryError, match="^compact_token_accounting_failed$"):
        value.prepare_compact_chat(
            work,
            settings(token_budget=replace(bound_budget(), counter=counter)),
            now_ms=NOW,
        )
    assert (
        value.chat_context_receipt(
            work.identity.owner_id,
            work.identity.key_fingerprint,
            entry.id,
            now_ms=NOW + 1,
        )
        is None
    )


def test_frame_counter_failure_after_instruction_count_rolls_back_receipt(tmp_path):
    value = store(tmp_path)
    entry, work = accepted(value, question="Объясни наблюдения за неделю")
    calls = []

    def counter(body):
        calls.append(body)
        return len(body) if len(calls) == 1 else 0

    with pytest.raises(HistoryError, match="^compact_token_accounting_failed$"):
        value.prepare_compact_chat(
            work, settings(token_budget=bound_budget(counter=counter)), now_ms=NOW
        )
    assert len(calls) == 2
    assert json.loads(calls[1])["question"] == work.request.text
    assert (
        value.chat_context_receipt(
            work.identity.owner_id,
            work.identity.key_fingerprint,
            entry.id,
            now_ms=NOW + 1,
        )
        is None
    )
