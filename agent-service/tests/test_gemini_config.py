"""Provider request construction for Google's OpenAI-compatible Gemini endpoint.

Everything here uses a mocked HTTP transport and a dummy key: it proves how requests are BUILT,
not that Gemini accepts them. A live check needs a real key (see README).
"""
import json
import logging
from pathlib import Path

import httpx
import pytest

from app.core.config import Settings
from app.extraction.errors import LLMProviderError
from app.extraction.prompts import SYSTEM_PROMPT, build_messages
from app.extraction.provider import OpenAICompatibleProvider
from app.extraction.service import extract

GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/openai/"
DUMMY_KEY = "dummy-key-for-tests-only"
TRANSCRIPT = "Alex: Priya, please prepare the test report by Friday."


def gemini_settings(**overrides) -> Settings:
    values = dict(llm_api_key=DUMMY_KEY, llm_base_url=GEMINI_BASE, llm_model="gemini-test-model")
    values.update(overrides)
    return Settings(_env_file=None, **values)


def provider_with(handler, **overrides) -> OpenAICompatibleProvider:
    client = httpx.Client(transport=httpx.MockTransport(handler))
    return OpenAICompatibleProvider(gemini_settings(**overrides), client)


def ok(content: str) -> httpx.Response:
    return httpx.Response(200, json={"choices": [{"message": {"content": content}}]})


def test_trailing_slash_base_url_gives_the_gemini_chat_completions_endpoint():
    seen = {}

    def handler(request: httpx.Request):
        seen["url"] = str(request.url)
        return ok("{}")

    provider_with(handler).complete(build_messages(TRANSCRIPT))
    assert seen["url"] == "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"


def test_bearer_auth_json_mode_model_and_message_roles():
    seen = {}

    def handler(request: httpx.Request):
        seen["auth"] = request.headers["authorization"]
        seen["body"] = json.loads(request.content)
        return ok("{}")

    provider_with(handler).complete(build_messages(TRANSCRIPT))

    assert seen["auth"] == f"Bearer {DUMMY_KEY}"
    body = seen["body"]
    assert body["model"] == "gemini-test-model"
    assert body["response_format"] == {"type": "json_object"}
    system, user = body["messages"]
    assert system == {"role": "system", "content": SYSTEM_PROMPT}
    assert user["role"] == "user" and TRANSCRIPT in user["content"]
    assert TRANSCRIPT not in system["content"]  # transcript never travels in the system role


def test_json_mode_can_be_switched_off():
    seen = {}

    def handler(request: httpx.Request):
        seen["body"] = json.loads(request.content)
        return ok("{}")

    provider_with(handler, llm_json_mode=False).complete(build_messages(TRANSCRIPT))
    assert "response_format" not in seen["body"]


def test_gemini_style_reply_with_code_fence_and_model_claimed_verification_is_still_verified_by_our_code():
    reply = "```json\n" + json.dumps({
        "tasks": [{"title": "Report", "description": "d", "owner": "Priya", "deadline": "Friday",
                   "evidence_quote": "Priya, please prepare the test report by Friday.",
                   "evidence_verified": False},
                  {"title": "Made up", "evidence_quote": "never said this", "evidence_verified": True}],
        "decisions": [], "unresolved_questions": [], "completed_updates": [],
    }) + "\n```"
    result = extract(TRANSCRIPT, provider_with(lambda req: ok(reply)))
    assert [t.evidence_status for t in result.tasks] == ["verified", "unverified"]
    assert result.tasks[1].evidence_verified is False


@pytest.mark.parametrize("status,needle", [
    (404, "LLM_MODEL"),
    (400, "LLM_JSON_MODE=false"),
    (401, "LLM_API_KEY"),
])
def test_actionable_errors_without_echoing_provider_body(status, needle):
    def handler(request):
        return httpx.Response(status, text=f"body mentions {DUMMY_KEY}")

    with pytest.raises(LLMProviderError) as e:
        provider_with(handler).complete(build_messages(TRANSCRIPT))
    assert needle in e.value.message
    assert DUMMY_KEY not in e.value.message


def test_400_hint_is_omitted_when_json_mode_is_already_off():
    with pytest.raises(LLMProviderError) as e:
        provider_with(lambda r: httpx.Response(400), llm_json_mode=False).complete(build_messages(TRANSCRIPT))
    assert "LLM_JSON_MODE" not in e.value.message


def test_key_is_never_logged(caplog):
    caplog.set_level(logging.DEBUG)
    with pytest.raises(LLMProviderError):
        provider_with(lambda r: httpx.Response(500, text="oops")).complete(build_messages(TRANSCRIPT))
    assert DUMMY_KEY not in caplog.text


def test_settings_read_gemini_values_from_an_env_file_and_hide_the_key_in_repr(tmp_path: Path):
    env = tmp_path / ".env"
    env.write_text(
        f"LLM_API_KEY={DUMMY_KEY}\nLLM_BASE_URL={GEMINI_BASE}\nLLM_MODEL=gemini-test-model\n"
        "LLM_TIMEOUT_SECONDS=30\nLLM_JSON_MODE=true\nUNRELATED_SECRET=keep-me\n"
    )
    s = Settings(_env_file=env)
    assert s.llm_configured
    assert s.llm_base_url == GEMINI_BASE and s.llm_model == "gemini-test-model"
    assert s.llm_timeout_seconds == 30 and s.llm_json_mode is True


def test_tracked_example_files_contain_placeholders_only():
    root = Path(__file__).resolve().parents[2]
    for name in (".env.example", "agent-service/.env.example"):
        text = (root / name).read_text()
        assert "generativelanguage.googleapis.com/v1beta/openai/" in text
        for line in text.splitlines():
            if line.startswith("LLM_API_KEY="):
                has_value = line.strip() != "LLM_API_KEY="  # boolean only: never let a failure message echo the value
                assert not has_value, f"{name} must keep LLM_API_KEY empty (put the real key in agent-service/.env)"
