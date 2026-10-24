from fastapi import APIRouter

from app.core.config import ENV_FILE, get_settings
from app.extraction.provider import check_connectivity

router = APIRouter()


@router.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP", "service": "meet2action-agent-service"}


@router.get("/ready")
def ready(verify: bool = False) -> dict:
    """Configuration status. Reports names and booleans only; never a secret value.

    `llm_configured` only means the settings are present. `llm_connectivity` stays "not_checked" (and
    `llm_connectivity_verified` false) unless you call `/ready?verify=true`, which makes one token-free
    request to the provider (retrieve the configured model) and reports the outcome.
    """
    s = get_settings()
    connectivity = check_connectivity(s) if verify else "not_checked"
    return {
        "status": "UP",
        "llm_configured": s.llm_configured,
        "llm_api_key_set": bool(s.llm_api_key.strip()),
        "llm_model": s.llm_model or None,
        "llm_base_url": s.llm_base_url,
        "llm_missing": s.llm_missing,
        "env_file_found": ENV_FILE.is_file(),
        "llm_connectivity": connectivity,
        "llm_connectivity_verified": connectivity == "ok",
    }
