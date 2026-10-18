import hashlib
import json
from dataclasses import replace

from app.ai.compact_context import FocusWindow, TokenBudget
from app.ai.schemas import ChatRequest, ChatResult
from test_ai_compact_chat import MINUTE, START, accepted, module, point, prepare
from test_ai_history_contract import NOW, body, record
from test_ai_history_store import KEY, OWNER, store
from test_ai_native_text import MODEL
from test_ai_server_chat import job


def archive(value, index, *, question, answer=None):
    at = NOW - (30 - index) * 1000
    entry = job(created_ms=at)
    policy = module().compact_chat_policy(MODEL)
    value.accept_chat(entry, ChatRequest(question), policy, now_ms=at)
    if answer is not None:
        value.finish_chat(
            replace(entry, state="SUCCEEDED", started_ms=at + 1, finished_ms=at + 2),
            ChatResult(answer),
            now_ms=at + 2,
        )
    return entry


def test_prior_text_is_prunable_and_exchange_provenance_remains(tmp_path):
    value = store(tmp_path)
    prior = archive(value, 0, question="Earlier question", answer="Earlier answer")
    pending = archive(value, 1, question="Unknown outcome")
    _, work = accepted(value)
    doc = json.loads(prepare(value, work).input_body)
    exchanges = doc["suppliedFacts"]["serverConversation"]["exchanges"]
    assert [e["jobId"] for e in exchanges] == [prior.id, pending.id]
    turns = {t["reference"]: t for t in doc["conversation"]}
    assert turns[exchanges[0]["questionReference"]]["text"] == "Earlier question"
    assert turns[exchanges[0]["answerReference"]]["role"] == "assistant"
    assert turns[exchanges[0]["answerReference"]]["text"] == "Earlier answer"
    assert exchanges[0]["finishedAtMs"] == prior.created_ms + 2
    assert exchanges[1]["state"] == "OUTCOME_NOT_RECORDED"
    assert exchanges[1]["answerReference"] is None
    assert all("question" not in e and "answer" not in e for e in exchanges)
    assert work.request.text == doc["question"]
    assert work.request.text not in [t["text"] for t in turns.values()]


def test_token_pressure_prunes_old_answers_not_exact_focus_events(tmp_path):
    value = store(tmp_path)
    originals = [
        archive(
            value,
            i,
            question=f"Question {i} " + "q" * 700,
            answer=f"Answer {i} " + "a" * 1100,
        )
        for i in range(8)
    ]
    meal = record(
        kind="MEAL",
        eventAtMs=START + 5 * MINUTE,
        observedAtMs=NOW,
        data={"carbsGrams": 24.0, "foodType": "MIXED", "quantitySource": "APPROXIMATE"},
    )
    value.append(OWNER, KEY, body([meal, point(0), point(10)]), now_ms=NOW)
    entry, work = accepted(value, question="Keep the meal and this exact question")
    result = prepare(
        value,
        work,
        focus_windows=(FocusWindow(START, START + 10 * MINUTE, 1),),
        token_budget=TokenBudget(14_000, 1_000, "synthetic-byte-count", len),
    )
    doc = json.loads(result.input_body)
    assert len(result.input_body) <= 13_000
    assert doc["omittedChatTurns"] > 0
    assert doc["question"] == work.request.text
    assert doc["events"][0]["record"] == meal
    assert doc["glucose"]["coveredMinutes"] == 10
    assert {s["stepMinutes"] for s in doc["glucose"]["series"]} == {1, 10}
    assert len(doc["suppliedFacts"]["serverConversation"]["exchanges"]) > 0
    saved = value.chat_context_receipt(OWNER, KEY, entry.id, now_ms=NOW + 1)
    manifest = json.loads(saved)["manifest"]
    assert manifest["omittedChatTurns"] == doc["omittedChatTurns"]
    assert manifest["frameDigest"] == hashlib.sha256(result.input_body).hexdigest()
    archived = value.chat_context(OWNER, KEY, now_ms=NOW + 1)
    answers = [e for e in archived["exchanges"] if e["jobId"] == originals[-1].id]
    assert answers[0]["answer"].endswith("a" * 1100)


def test_excerpt_flags_are_not_erased_by_turn_projection(tmp_path):
    value = store(tmp_path)
    archive(value, 0, question="q" * 2000, answer="a" * 3000)
    _, work = accepted(value)
    doc = json.loads(prepare(value, work).input_body)
    exchange = doc["suppliedFacts"]["serverConversation"]["exchanges"][0]
    assert exchange["questionTruncated"] and exchange["answerTruncated"]
    assert len(doc["conversation"]) == 2
    assert all(len(t["text"]) == 1200 for t in doc["conversation"])
    assert doc["omittedChatTurns"] == 0
