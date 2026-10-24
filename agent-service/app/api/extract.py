import logging

from fastapi import APIRouter, Depends, HTTPException

from app.core.config import get_settings
from app.extraction.errors import ExtractionError
from app.extraction.provider import LLMProvider, OpenAICompatibleProvider
from app.extraction.schemas import ExtractRequest, ExtractResponse
from app.extraction.service import extract

log = logging.getLogger(__name__)
router = APIRouter()


def get_provider() -> LLMProvider:
    return OpenAICompatibleProvider(get_settings())


@router.post("/extract", response_model=ExtractResponse)
def extract_endpoint(req: ExtractRequest, provider: LLMProvider = Depends(get_provider)) -> ExtractResponse:
    settings = get_settings()
    if len(req.transcript) > settings.max_transcript_chars:
        raise HTTPException(status_code=413, detail={
            "code": "transcript_too_large",
            "message": f"Transcript exceeds {settings.max_transcript_chars} characters",
        })
    try:
        return extract(req.transcript, provider)
    except ExtractionError as e:
        log.warning("Extraction failed: %s (%s)", e.code, e.message)
        raise HTTPException(status_code=e.status_code, detail={"code": e.code, "message": e.message})
