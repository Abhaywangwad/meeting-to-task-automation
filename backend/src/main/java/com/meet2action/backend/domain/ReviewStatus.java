package com.meet2action.backend.domain;

/** Human review state. Transitions are enforced in TaskService. */
public enum ReviewStatus {
    PENDING_REVIEW,
    APPROVED,
    REJECTED
}
