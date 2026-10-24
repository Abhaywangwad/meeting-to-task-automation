package com.meet2action.backend.domain;

/**
 * Trello sync state, independent of evidence and review status.
 * NOT_SYNCED -> SYNCING -> SYNCED | FAILED (definitely no card created) | UNCERTAIN (a card may exist).
 */
public enum SyncStatus {
    NOT_SYNCED,
    /** A sync (or reconciliation) attempt is in flight; only one is allowed at a time. */
    SYNCING,
    SYNCED,
    /** Trello definitively refused or was unreachable; no card was created. Safe to retry. */
    FAILED,
    /** Outcome unknown (timeout, 5xx, malformed reply). Must be reconciled before any new create. */
    UNCERTAIN
}
