"""Readiness: configured is not the same as verified. All HTTP is mocked; no real key or network is used."""
import httpx
import pytest
from fastapi.testclient import TestClient

import app.api.health as health
from app.core.config import Settings
from app.extraction.provider import check_connectivity
from app.main import app

KEY = "dummy-key-for-tests-only"
BASE = "https://generativelanguage.googleapis.com/v1beta/openai/"


def settings(**o) -> Settings:
    v = dict(llm_api_key=KEY, llm_base_url=BASE, llm_model="gemini-3.8-flash")
    v.update(o)
    return Settings(_env_file=None, **v)


def client_for(handler) -> httpx.Client:
    return httpx.Client(transport=httpx.MockTransport(handler))


def test_probe_retrieves_the_configured_model_with_bearer_auth():
    seen = {}

    def handler(request: httpx.Request):
        seen["url"], seen["auth"], seen["method"] = str(request.url), request.headers["authorization"], request.method
        return httpx.Response(200, json={"id": "gemini-3.8-flash"})

    assert check_connectivity(settings(), client_for(handler)) == "ok"
    assert seen["url"] == BASE + "models/gemini-3.8-flash"
    assert seen["method"] == "GET" and seen["auth"] == f"Bearer {KEY}"


def test_models_prefix_in_the_model_name_is_tolerated():
    seen = {}
    check_connectivity(settings(llm_model="models/gemini-3.8-flash"),
                       client_for(lambda r: seen.setdefault("url", str(r.url)) and httpx.Response(200)))
    assert seen["url"].endswith("/models/gemini-3.8-flash")


@pytest.mark.parametrize("status,expected", [(401, "auth_failed"), (403, "auth_failed"), (404, "model_not_found"),
                                             (429, "rate_limited"), (500, "error")])
def test_probe_outcomes(status, expected):
    assert check_connectivity(settings(), client_for(lambda r: httpx.Response(status, text=KEY))) == expected


def test_probe_timeouts_and_network_errors():
    def slow(request):
        raise httpx.ReadTimeout("slow")

    def down(request):
        raise httpx.ConnectError("refused")

    assert check_connectivity(settings(), client_for(slow)) == "timeout"
    assert check_connectivity(settings(), client_for(down)) == "unreachable"


def test_probe_makes_no_request_when_not_configured():
    def boom(request):
        raise AssertionError("no request may be sent without configuration")

    assert check_connectivity(settings(llm_api_key=""), client_for(boom)) == "not_configured"
    assert check_connectivity(settings(llm_model=""), client_for(boom)) == "not_configured"


def test_ready_is_configured_but_not_verified_by_default_and_makes_no_network_call(monkeypatch):
    monkeypatch.setattr(health, "get_settings", lambda: settings())
    monkeypatch.setattr(health, "check_connectivity", lambda s: (_ for _ in ()).throw(AssertionError("no probe")))
    r = TestClient(app).get("/ready")
    body = r.json()
    assert body["llm_configured"] is True
    assert body["llm_connectivity"] == "not_checked" and body["llm_connectivity_verified"] is False
    assert KEY not in r.text


def test_ready_verify_reports_the_probe_result_without_the_key(monkeypatch):
    monkeypatch.setattr(health, "get_settings", lambda: settings())
    monkeypatch.setattr(health, "check_connectivity", lambda s: "model_not_found")
    r = TestClient(app).get("/ready?verify=true")
    assert r.json()["llm_connectivity"] == "model_not_found"
    assert r.json()["llm_connectivity_verified"] is False
    assert KEY not in r.text

    monkeypatch.setattr(health, "check_connectivity", lambda s: "ok")
    assert TestClient(app).get("/ready?verify=true").json()["llm_connectivity_verified"] is True


def test_ready_verify_without_configuration_is_not_configured(monkeypatch):
    monkeypatch.setattr(health, "get_settings", lambda: settings(llm_api_key="", llm_model=""))
    body = TestClient(app).get("/ready?verify=true").json()
    assert body["llm_connectivity"] == "not_configured" and body["llm_connectivity_verified"] is False
