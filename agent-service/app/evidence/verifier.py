"""Evidence verification, independent of the LLM and of model confidence.

Rule: a quote is verified if it occurs in the transcript as an exact substring, or,
failing that, after collapsing runs of whitespace (spaces, tabs, newlines) to a
single space on both sides. Comparison stays case-sensitive. The original quote is
never modified; offsets always refer to the ORIGINAL transcript.
"""
import re
from dataclasses import dataclass
from typing import Optional

_WS = re.compile(r"\s+")


@dataclass(frozen=True)
class EvidenceResult:
    verified: bool
    start: Optional[int] = None
    end: Optional[int] = None
    match: Optional[str] = None  # "exact" | "whitespace_normalized"


UNVERIFIED = EvidenceResult(verified=False)


def _normalize_with_map(text: str) -> tuple[str, list[int]]:
    """Collapse whitespace runs; return normalized text and, per normalized char,
    the index of the corresponding character in the original text."""
    out: list[str] = []
    index_map: list[int] = []
    pos = 0
    for m in _WS.finditer(text):
        for i in range(pos, m.start()):
            out.append(text[i])
            index_map.append(i)
        out.append(" ")
        index_map.append(m.start())
        pos = m.end()
    for i in range(pos, len(text)):
        out.append(text[i])
        index_map.append(i)
    return "".join(out), index_map


def verify_quote(transcript: str, quote: Optional[str]) -> EvidenceResult:
    if quote is None or not quote.strip():
        return UNVERIFIED

    idx = transcript.find(quote)
    if idx != -1:
        return EvidenceResult(True, idx, idx + len(quote), "exact")

    norm_quote = _WS.sub(" ", quote).strip()
    norm_transcript, index_map = _normalize_with_map(transcript)
    nidx = norm_transcript.find(norm_quote)
    if nidx == -1:
        return UNVERIFIED
    start = index_map[nidx]
    end = index_map[nidx + len(norm_quote) - 1] + 1
    return EvidenceResult(True, start, end, "whitespace_normalized")
