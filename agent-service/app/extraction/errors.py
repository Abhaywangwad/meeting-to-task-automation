class ExtractionError(Exception):
    """Base class; carries the HTTP status and a stable error code for the API."""

    status_code = 500
    code = "extraction_error"

    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


class LLMNotConfiguredError(ExtractionError):
    status_code = 503
    code = "llm_not_configured"


class LLMTimeoutError(ExtractionError):
    status_code = 504
    code = "llm_timeout"


class LLMRateLimitError(ExtractionError):
    status_code = 429
    code = "llm_rate_limited"


class LLMProviderError(ExtractionError):
    status_code = 502
    code = "llm_provider_error"


class LLMMalformedOutputError(ExtractionError):
    status_code = 502
    code = "llm_malformed_output"


class LLMProviderBusyError(ExtractionError):
    """Provider answered 503 (overloaded) even after bounded retries."""

    status_code = 503
    code = "llm_provider_busy"
