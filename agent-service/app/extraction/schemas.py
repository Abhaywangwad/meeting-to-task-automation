"""Pydantic models: what the LLM may return, and what the API returns.

The LLM-facing models deliberately have no verification fields; any such keys the
model sends are ignored (extra="ignore"). Verification is set only by application code.
"""
from typing import Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, field_validator

EvidenceStatus = Literal["verified", "unverified"]
EvidenceMatch = Literal["exact", "whitespace_normalized"]


class ExtractRequest(BaseModel):
    transcript: str

    @field_validator("transcript")
    @classmethod
    def not_blank(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("transcript must not be empty or whitespace-only")
        return v


# ---------- LLM output (untrusted) ----------
class _LLMModel(BaseModel):
    model_config = ConfigDict(extra="ignore")


class LLMTask(_LLMModel):
    title: str = Field(min_length=1)
    description: str = ""
    owner: Optional[str] = None
    deadline: Optional[str] = None
    evidence_quote: Optional[str] = None
    confidence: Optional[float] = Field(default=None, ge=0, le=1)


class LLMStatement(_LLMModel):
    text: str = Field(min_length=1)
    owner: Optional[str] = None
    evidence_quote: Optional[str] = None


class LLMExtraction(_LLMModel):
    tasks: list[LLMTask] = []
    decisions: list[LLMStatement] = []
    unresolved_questions: list[LLMStatement] = []
    completed_updates: list[LLMStatement] = []


# ---------- API response ----------
class Evidence(BaseModel):
    """Result of checking a quote against the transcript.

    A match proves only that the words occur in the transcript, not that the
    model interpreted them correctly.
    """

    evidence_verified: bool
    evidence_status: EvidenceStatus
    evidence_start: Optional[int] = None
    evidence_end: Optional[int] = None
    evidence_match: Optional[EvidenceMatch] = None


class ProposedTask(Evidence):
    id: str
    title: str
    description: str
    owner: Optional[str]
    deadline: Optional[str]
    evidence_quote: str
    confidence: Optional[float] = None


class ProposedStatement(Evidence):
    id: str
    text: str
    owner: Optional[str] = None
    evidence_quote: str


class ExtractResponse(BaseModel):
    tasks: list[ProposedTask]
    decisions: list[ProposedStatement]
    unresolved_questions: list[ProposedStatement]
    completed_updates: list[ProposedStatement]
