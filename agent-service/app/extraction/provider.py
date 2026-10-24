"""LLM provider communication only: no prompts, schemas, or verification here."""
import time
from typing import Protocol

import httpx

from app.core.config import Settings
from app.extraction.errors import (
    LLMNotConfiguredError,
    LLMProviderBusyError,
    LLMProviderError,
    LLMRateLimitError,
    LLMTimeoutError,
)


class LLMProvider(Protocol):
    def complete(self, messages: list[dict[str, str]]) -> str:
        """Send chat messages; return the assistant message content (raw text)."""
        ...


# Waits before the 2nd and 3rd attempt when the provider answers with a transient server error.
# Extraction has no side effects, so retrying these is safe. Timeouts and 4xx are never retried.
BUSY_BACKOFF_SECONDS = (2.0, 4.0)
RETRYABLE_STATUSES = frozenset({500, 502, 503, 504})


class OpenAICompatibleProvider:
    def __init__(self, settings: Settings, client: httpx.Client | None = None, sleep=time.sleep):
        self._settings = settings
        self._client = client
        self._sleep = sleep

    def complete(self, messages: list[dict[str, str]]) -> str:
        s = self._settings
        if not s.llm_configured:
            raise LLMNotConfiguredError(
                "LLM is not configured. Set LLM_API_KEY, LLM_MODEL (and LLM_BASE_URL) on the agent service."
            )
        payload: dict = {"model": s.llm_model, "messages": messages, "temperature": 0}
        if s.llm_json_mode:
            payload["response_format"] = {"type": "json_object"}
        url = s.llm_base_url.rstrip("/") + "/chat/completions"
        headers = {"Authorization": f"Bearer {s.llm_api_key}"}

        # HTTP 503 means the provider is overloaded and rejected the call before doing any work, so a short,
        # bounded retry is safe (extraction has no side effects). Timeouts are NOT retried.
        for attempt in range(len(BUSY_BACKOFF_SECONDS) + 1):
            try:
                if self._client is not None:
                    resp = self._client.post(url, json=payload, headers=headers, timeout=s.llm_timeout_seconds)
                else:
                    with httpx.Client(timeout=s.llm_timeout_seconds) as client:
                        resp = client.post(url, json=payload, headers=headers)
            except httpx.TimeoutException as e:
                raise LLMTimeoutError(f"LLM request timed out after {s.llm_timeout_seconds}s") from e
            except httpx.HTTPError as e:
                raise LLMProviderError(f"Could not reach LLM provider: {type(e).__name__}") from e
            if resp.status_code not in RETRYABLE_STATUSES:
                break
            if attempt < len(BUSY_BACKOFF_SECONDS):
                self._sleep(BUSY_BACKOFF_SECONDS[attempt])
        else:  # pragma: no cover - loop always breaks or raises below
            pass

        if resp.status_code == 503:
            raise LLMProviderBusyError(
                "The LLM provider is overloaded (HTTP 503: high demand) and did not accept the request after "
                f"{len(BUSY_BACKOFF_SECONDS) + 1} attempts. Try again in a minute, or set LLM_MODEL to another model."
            )
        if resp.status_code == 429:
            raise LLMRateLimitError("LLM provider rate limit reached; retry later")
        if resp.status_code in (401, 403):
            raise LLMProviderError("LLM provider rejected the credentials (check LLM_API_KEY)")
        if resp.status_code == 404:
            raise LLMProviderError(
                "LLM provider returned HTTP 404: the model or endpoint was not found "
                "(check LLM_MODEL is available to your project, and LLM_BASE_URL)"
            )
        if resp.status_code == 400:
            hint = " If the provider rejects JSON mode, set LLM_JSON_MODE=false." if s.llm_json_mode else ""
            raise LLMProviderError(
                f"LLM provider returned HTTP 400: the request was rejected (check LLM_MODEL).{hint}"
            )
        if resp.status_code >= 400:
            # Do not echo the provider body: it may contain request details.
            raise LLMProviderError(f"LLM provider returned HTTP {resp.status_code}")

        try:
            return resp.json()["choices"][0]["message"]["content"] or ""
        except (ValueError, KeyError, IndexError, TypeError) as e:
            raise LLMProviderError("LLM provider returned an unexpected response shape") from e


def check_connectivity(settings: Settings, client: httpx.Client | None = None) -> str:
    """Cheap, token-free probe: retrieve the configured model (GET {base}/models/{model}).

    Returns one of: ok, not_configured, auth_failed, model_not_found, rate_limited, timeout, unreachable, error.
    Never returns provider response bodies and never logs the key. A mocked or skipped probe is not "verified".
    """
    if not settings.llm_configured:
        return "not_configured"
    url = f"{settings.llm_base_url.rstrip('/')}/models/{settings.llm_model.removeprefix('models/')}"
    headers = {"Authorization": f"Bearer {settings.llm_api_key}"}
    try:
        if client is not None:
            resp = client.get(url, headers=headers, timeout=settings.llm_timeout_seconds)
        else:
            with httpx.Client(timeout=settings.llm_timeout_seconds) as c:
                resp = c.get(url, headers=headers)
    except httpx.TimeoutException:
        return "timeout"
    except httpx.HTTPError:
        return "unreachable"
    if resp.status_code == 200:
        return "ok"
    if resp.status_code in (401, 403):
        return "auth_failed"
    if resp.status_code == 404:
        return "model_not_found"
    if resp.status_code == 429:
        return "rate_limited"
    return "error"
