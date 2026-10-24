package com.meet2action.backend.domain;

public enum ExtractionStatus {
    /** Meeting saved; agent call in progress (or the process died mid-call). */
    PENDING,
    /** Agent returned a valid result (which may legitimately contain zero tasks). */
    COMPLETED,
    /** Agent call or result validation failed; no proposals were stored. */
    FAILED
}
