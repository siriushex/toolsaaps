from app.ai.compact_chat import compact_chat_policy
from app.ai.contextual_chat import contextual_chat_policy
from app.ai.job_policy import CHAT_POLICY, MEAL_PHOTO_POLICY


def test_every_chat_policy_requests_concise_grounded_plain_text():
    for policy in (CHAT_POLICY, contextual_chat_policy("test-model"), compact_chat_policy("test-model")):
        prompt = policy.system_prompt
        assert "Answer the question first" in prompt
        assert "80 words" in prompt
        assert "explicitly requests detail" in prompt
        assert "Never invent missing data" in prompt
        assert "plain text" in prompt
        assert "source and time" in prompt
        assert policy.prompt_revision.endswith("-concise-v1")
        assert not policy.allow_actions and not policy.allow_tools
        assert policy.max_deadline_ms == 900_000
        assert policy.max_result_chars == CHAT_POLICY.max_result_chars


def test_concise_chat_does_not_replace_medical_or_source_boundaries():
    contextual = contextual_chat_policy("test-model")
    compact = compact_chat_policy("test-model")
    assert "therapy commands" in CHAT_POLICY.system_prompt
    assert "untrusted data" in contextual.system_prompt
    assert "not necessarily current" in contextual.system_prompt
    assert "not confirmed delivery" in compact.system_prompt
    assert "80 words" not in MEAL_PHOTO_POLICY.system_prompt
    assert MEAL_PHOTO_POLICY.prompt_revision == "food-estimate-v1"
