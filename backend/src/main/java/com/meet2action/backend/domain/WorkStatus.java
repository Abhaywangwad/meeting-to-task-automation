package com.meet2action.backend.domain;

/**
 * Where the work is, independent of review status (is it approved?) and sync status (is the Trello card in sync?).
 * Maps to a Trello list: TO_DO, IN_PROGRESS, COMPLETED. Only changed by an explicit user action, never implicitly.
 */
public enum WorkStatus {
    TO_DO,
    IN_PROGRESS,
    COMPLETED
}
