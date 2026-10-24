from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

# agent-service/ (this file is agent-service/app/core/config.py). The local, git-ignored settings file is
# found relative to the code, NOT the current working directory, so starting uvicorn from any folder works.
AGENT_DIR = Path(__file__).resolve().parents[2]
ENV_FILE = AGENT_DIR / ".env"


class Settings(BaseSettings):
    """Configuration from real environment variables, then agent-service/.env.

    Precedence (highest first): process environment, ./.env in the current directory, agent-service/.env.
    Only `.env` is loaded; `.env.example` is a tracked template and is never read.
    """

    model_config = SettingsConfigDict(env_file=(str(ENV_FILE), ".env"), extra="ignore")

    agent_service_host: str = "0.0.0.0"
    agent_service_port: int = 8000
    cors_allowed_origins: str = "http://localhost:5173,http://127.0.0.1:5173"

    # OpenAI-compatible chat-completions provider (default: Google Gemini). Credentials stay server-side.
    llm_api_key: str = ""
    llm_base_url: str = "https://generativelanguage.googleapis.com/v1beta/openai/"
    llm_model: str = ""
    llm_timeout_seconds: float = 30.0
    # Ask the provider for JSON-object output (set false if it rejects response_format).
    llm_json_mode: bool = True

    max_transcript_chars: int = 200_000

    @property
    def cors_origins(self) -> list[str]:
        return [o.strip() for o in self.cors_allowed_origins.split(",") if o.strip()]

    @property
    def llm_missing(self) -> list[str]:
        """Names (never values) of the required LLM settings that are empty."""
        required = {
            "LLM_API_KEY": self.llm_api_key,
            "LLM_MODEL": self.llm_model,
            "LLM_BASE_URL": self.llm_base_url,
        }
        return [name for name, value in required.items() if not value.strip()]

    @property
    def llm_configured(self) -> bool:
        return not self.llm_missing


@lru_cache
def get_settings() -> Settings:
    return Settings()
