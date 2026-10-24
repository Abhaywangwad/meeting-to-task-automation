"""Provider overload (HTTP 503) handling: bounded retry, truthful error. Mocked HTTP; no real key or network."""
import httpx
import pytest

from app.core.config import Settings
from app.extraction.errors import LLMProviderBusyError, LLMTimeoutError
from app.extraction.prompts import build_messages
from app.extraction.provider import BUSY_BACKOFF_SECONDS, OpenAICompatibleProvider

KEY = "dummy-key-for-tests-only"


def provider(handler, sleeps):
    settings = Settings(_env_file=None, llm_api_key=KEY, llm_model="m")
    client = httpx.Client(transport=httpx.MockTransport(handler))
    return OpenAICompatibleProvider(settings, client, sleep=sleeps.append)


def ok():
    return httpx.Response(200, json={"choices": [{"message": {"content": "{}"}}]})


def test_recovers_when_a_retry_succeeds():
    calls, sleeps = [], []

    def handler(request):
        calls.append(1)
        return httpx.Response(503, text="high demand") if len(calls) < 3 else ok()

    assert provider(handler, sleeps).complete(build_messages("x")) == "{}"
    assert len(calls) == 3 and sleeps == list(BUSY_BACKOFF_SECONDS)


def test_persistent_503_becomes_a_clear_busy_error_after_bounded_attempts():
    calls, sleeps = [], []

    def handler(request):
        calls.append(1)
        return httpx.Response(503, text=f"body with {KEY}")

    with pytest.raises(LLMProviderBusyError) as e:
        provider(handler, sleeps).complete(build_messages("x"))
    assert len(calls) == len(BUSY_BACKOFF_SECONDS) + 1
    assert e.value.status_code == 503 and e.value.code == "llm_provider_busy"
    assert "overloaded" in e.value.message and KEY not in e.value.message


def test_timeouts_are_never_retried():
    calls, sleeps = [], []

    def handler(request):
        calls.append(1)
        raise httpx.ReadTimeout("slow")

    with pytest.raises(LLMTimeoutError):
        provider(handler, sleeps).complete(build_messages("x"))
    assert len(calls) == 1 and sleeps == []


def test_other_errors_are_not_retried():
    calls = []

    def handler(request):
        calls.append(1)
        return httpx.Response(401)

    with pytest.raises(Exception):
        provider(handler, []).complete(build_messages("x"))
    assert len(calls) == 1


@pytest.mark.parametrize("status", [500, 502, 504])
def test_transient_server_errors_are_retried_then_reported_as_provider_error(status):
    from app.extraction.errors import LLMProviderError

    calls, sleeps = [], []

    def handler(request):
        calls.append(1)
        return httpx.Response(status, text="x")

    with pytest.raises(LLMProviderError) as e:
        provider(handler, sleeps).complete(build_messages("x"))
    assert len(calls) == len(BUSY_BACKOFF_SECONDS) + 1
    assert f"HTTP {status}" in e.value.message
