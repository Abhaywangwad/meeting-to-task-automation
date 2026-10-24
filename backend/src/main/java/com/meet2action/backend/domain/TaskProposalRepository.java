package com.meet2action.backend.domain;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskProposalRepository extends JpaRepository<TaskProposal, String> {

    List<TaskProposal> findAllByOrderByCreatedAtAscPositionAsc();

    List<TaskProposal> findByMeetingIdOrderByPositionAsc(String meetingId);

    List<TaskProposal> findByReviewStatusOrderByCreatedAtAscPositionAsc(ReviewStatus reviewStatus);

    List<TaskProposal> findByMeetingIdAndReviewStatusOrderByPositionAsc(String meetingId, ReviewStatus reviewStatus);

    /**
     * Atomically claims the single active sync attempt. The WHERE clause is the real gate: it only matches
     * a task that is still APPROVED, evidence-verified, at the expected version and not syncing/synced/uncertain.
     * Returns 1 for the one winner, 0 for everyone else. Must run inside a transaction.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TaskProposal t set t.syncStatus = com.meet2action.backend.domain.SyncStatus.SYNCING,
                t.syncAttemptedAt = :now, t.updatedAt = :now, t.version = t.version + 1,
                t.syncErrorCode = null, t.syncErrorMessage = null
            where t.id = :id and t.version = :version
              and t.reviewStatus = com.meet2action.backend.domain.ReviewStatus.APPROVED
              and t.evidenceStatus = com.meet2action.backend.domain.EvidenceStatus.VERIFIED
              and t.syncStatus in (com.meet2action.backend.domain.SyncStatus.NOT_SYNCED,
                                   com.meet2action.backend.domain.SyncStatus.FAILED)
            """)
    int claimForSync(@Param("id") String id, @Param("version") long version, @Param("now") Instant now);

    /**
     * Atomically claims the single in-flight Trello list move for a task. Only matches an approved task that has a
     * synced card, is at the expected version, is not already in the target state, and has no live move claim
     * (a claim older than {@code staleBefore} counts as abandoned). Returns 1 for the winner, 0 otherwise.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TaskProposal t set t.workMoveTarget = :target, t.workMoveStartedAt = :now,
                t.updatedAt = :now, t.version = t.version + 1
            where t.id = :id and t.version = :version
              and t.reviewStatus = com.meet2action.backend.domain.ReviewStatus.APPROVED
              and t.syncStatus = com.meet2action.backend.domain.SyncStatus.SYNCED
              and t.trelloCardId is not null
              and t.workStatus <> :target
              and (t.workMoveTarget is null or t.workMoveStartedAt < :staleBefore)
            """)
    int claimForMove(@Param("id") String id, @Param("version") long version, @Param("target") WorkStatus target,
                     @Param("now") Instant now, @Param("staleBefore") Instant staleBefore);

    /** Claims reconciliation for an UNCERTAIN task, or a SYNCING one whose attempt is stale (crashed). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TaskProposal t set t.syncStatus = com.meet2action.backend.domain.SyncStatus.SYNCING,
                t.syncAttemptedAt = :now, t.updatedAt = :now, t.version = t.version + 1
            where t.id = :id
              and (t.syncStatus = com.meet2action.backend.domain.SyncStatus.UNCERTAIN
                   or (t.syncStatus = com.meet2action.backend.domain.SyncStatus.SYNCING
                       and t.syncAttemptedAt < :staleBefore))
            """)
    int claimForReconcile(@Param("id") String id, @Param("now") Instant now, @Param("staleBefore") Instant staleBefore);
}
