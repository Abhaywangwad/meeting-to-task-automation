"""Evidence validation: checks proposed quotes against the source transcript."""
from app.evidence.verifier import EvidenceResult, verify_quote

__all__ = ["EvidenceResult", "verify_quote"]
