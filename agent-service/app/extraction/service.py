"""Extraction orchestration: prompt -> provider -> schema validation -> evidence check."""
import json
import re
import uuid

from pydantic import ValidationError

from app.evidence import verify_quote
from app.extraction.errors import LLMMalformedOutputError
from app.extraction.prompts import build_messages
from app.extraction.provider import LLMProvider
from app.extraction.schemas import (
    ExtractResponse,
    LLMExtraction,
    LLMStatement,
    ProposedStatement,
    ProposedTask,
)

_FENCE = re.compile(r"^```(?:json)?\s*|\s*```$", re.IGNORECASE)


def parse_llm_output(raw: str) -> LLMExtraction:
    text = _FENCE.sub("", raw.strip())
    try:
        data = json.loads(text)
    except json.JSONDecodeError as e:
        raise LLMMalformedOutputError("LLM returned output that is not valid JSON") from e
    if not isinstance(data, dict):
        raise LLMMalformedOutputError("LLM output must be a JSON object")
    try:
        return LLMExtraction.model_validate(data)
    except ValidationError as e:
        raise LLMMalformedOutputError(
            f"LLM output did not match the expected schema ({len(e.errors())} problem(s))"
        ) from e


def _blank_to_none(value: str | None) -> str | None:
    if value is None:
        return None
    return value.strip() or None


def _evidence_fields(transcript: str, quote: str) -> dict:
    ev = verify_quote(transcript, quote)
    return {
        "evidence_verified": ev.verified,
        "evidence_status": "verified" if ev.verified else "unverified",
        "evidence_start": ev.start,
        "evidence_end": ev.end,
        "evidence_match": ev.match,
    }


def _statement(transcript: str, s: LLMStatement) -> ProposedStatement:
    quote = s.evidence_quote or ""
    return ProposedStatement(
        id=str(uuid.uuid4()),
        text=s.text,
        owner=_blank_to_none(s.owner),
        evidence_quote=quote,
        **_evidence_fields(transcript, quote),
    )


def extract(transcript: str, provider: LLMProvider) -> ExtractResponse:
    raw = provider.complete(build_messages(transcript))
    parsed = parse_llm_output(raw)

    tasks = []
    for t in parsed.tasks:
        quote = t.evidence_quote or ""
        tasks.append(
            ProposedTask(
                id=str(uuid.uuid4()),
                title=t.title,
                description=t.description,
                owner=_blank_to_none(t.owner),
                deadline=_blank_to_none(t.deadline),
                evidence_quote=quote,
                confidence=t.confidence,
                **_evidence_fields(transcript, quote),
            )
        )

    return ExtractResponse(
        tasks=tasks,
        decisions=[_statement(transcript, s) for s in parsed.decisions],
        unresolved_questions=[_statement(transcript, s) for s in parsed.unresolved_questions],
        completed_updates=[_statement(transcript, s) for s in parsed.completed_updates],
    )
