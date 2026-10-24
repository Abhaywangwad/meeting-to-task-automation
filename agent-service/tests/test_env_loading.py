"""Environment loading and readiness. Uses a dummy key and temp files only; nothing is read from the real .env."""
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import app.api.health as health
from app.core import config
from app.core.config import Settings
from app.main import app

DUMMY_KEY = "dummy-key-for-tests-only"
GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/openai/"


def write_env(path: Path, **values) -> Path:
    path.write_text("".join(f"{k}={v}\n" for k, v in values.items()) + "UNRELATED_SECRET=keep-me\n")
    return path


@pytest.fixture(autouse=True)
def clean_process_env(monkeypatch):
    for name in ("LLM_API_KEY", "LLM_BASE_URL", "LLM_MODEL", "LLM_TIMEOUT_SECONDS", "LLM_JSON_MODE"):
        monkeypatch.delenv(name, raising=False)


def test_env_file_is_resolved_from_the_code_location_not_the_working_directory():
    assert config.ENV_FILE.is_absolute()
    assert config.ENV_FILE.name == ".env"
    assert config.ENV_FILE.parent.name == "agent-service"
    assert str(config.ENV_FILE) in Settings.model_config["env_file"]
    assert ".env.example" not in " ".join(Settings.model_config["env_file"])  # the template is never loaded


def test_settings_load_all_llm_variables_even_when_started_from_another_directory(tmp_path, monkeypatch):
    env = write_env(tmp_path / ".env", LLM_API_KEY=DUMMY_KEY, LLM_BASE_URL=GEMINI_BASE,
                    LLM_MODEL="gemini-3.8-flash", LLM_TIMEOUT_SECONDS="45", LLM_JSON_MODE="false")
    elsewhere = tmp_path / "somewhere_else"
    elsewhere.mkdir()
    monkeypatch.chdir(elsewhere)
    monkeypatch.setitem(Settings.model_config, "env_file", (str(env), ".env"))  # stands in for agent-service/.env

    s = Settings()

    assert s.llm_configured and s.llm_missing == []
    assert s.llm_api_key == DUMMY_KEY
    assert s.llm_base_url == GEMINI_BASE and s.llm_model == "gemini-3.8-flash"
    assert s.llm_timeout_seconds == 45 and s.llm_json_mode is False


def test_process_environment_overrides_the_file(tmp_path, monkeypatch):
    env = write_env(tmp_path / ".env", LLM_API_KEY=DUMMY_KEY, LLM_MODEL="from-file")
    monkeypatch.setitem(Settings.model_config, "env_file", (str(env),))
    monkeypatch.setenv("LLM_MODEL", "from-process")
    assert Settings().llm_model == "from-process"


def test_defaults_target_gemini_and_nothing_is_configured_without_a_key_and_model(tmp_path, monkeypatch):
    monkeypatch.setitem(Settings.model_config, "env_file", (str(tmp_path / "missing.env"),))
    s = Settings()
    assert s.llm_base_url == GEMINI_BASE
    assert not s.llm_configured
    assert s.llm_missing == ["LLM_API_KEY", "LLM_MODEL"]


@pytest.mark.parametrize("values,missing", [
    ({"LLM_API_KEY": DUMMY_KEY}, ["LLM_MODEL"]),
    ({"LLM_MODEL": "gemini-3.8-flash"}, ["LLM_API_KEY"]),
    ({"LLM_API_KEY": "   ", "LLM_MODEL": "gemini-3.8-flash"}, ["LLM_API_KEY"]),
    ({"LLM_API_KEY": DUMMY_KEY, "LLM_MODEL": "m", "LLM_BASE_URL": ""}, ["LLM_BASE_URL"]),
])
def test_both_key_and_model_are_required(values, missing):
    s = Settings(_env_file=None, **{k.lower(): v for k, v in values.items()})
    assert not s.llm_configured
    assert s.llm_missing == missing


def ready_with(monkeypatch, **overrides):
    values = dict(llm_api_key="", llm_model="")
    values.update(overrides)
    monkeypatch.setattr(health, "get_settings", lambda: Settings(_env_file=None, **values))
    return TestClient(app).get("/ready")


def test_ready_reports_missing_names_without_exposing_values(monkeypatch):
    r = ready_with(monkeypatch)
    body = r.json()
    assert r.status_code == 200
    assert body["llm_configured"] is False and body["llm_api_key_set"] is False
    assert body["llm_missing"] == ["LLM_API_KEY", "LLM_MODEL"]
    assert body["llm_base_url"] == GEMINI_BASE


def test_ready_detects_key_and_model_but_never_returns_the_key(monkeypatch):
    r = ready_with(monkeypatch, llm_api_key=DUMMY_KEY, llm_model="gemini-3.8-flash")
    body = r.json()
    assert body["llm_configured"] is True and body["llm_api_key_set"] is True
    assert body["llm_model"] == "gemini-3.8-flash" and body["llm_missing"] == []
    assert DUMMY_KEY not in r.text


def test_ready_flags_a_key_without_a_model_as_not_configured(monkeypatch):
    body = ready_with(monkeypatch, llm_api_key=DUMMY_KEY).json()
    assert body["llm_configured"] is False and body["llm_api_key_set"] is True
    assert body["llm_missing"] == ["LLM_MODEL"]
