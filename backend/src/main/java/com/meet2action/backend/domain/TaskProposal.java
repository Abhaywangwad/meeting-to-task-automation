package com.meet2action.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.Length;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "task_proposals", indexes = {
        @Index(name = "idx_tasks_meeting", columnList = "meeting_id, position"),
        @Index(name = "idx_tasks_review_status", columnList = "review_status")
})
public class TaskProposal {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_id", nullable = false, updatable = false)
    private Meeting meeting;

    @Column(nullable = false)
    private int position;

    @Column(length = 500, nullable = false)
    private String title;

    @Column(length = Length.LONG32, nullable = false)
    private String description = "";

    @Column(length = 255)
    private String owner;

    @Column(length = 255)
    private String deadline;

    @Column(length = Length.LONG32, name = "evidence_quote", nullable = false)
    private String evidenceQuote = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_status", length = 16, nullable = false)
    private EvidenceStatus evidenceStatus;

    @Column(name = "evidence_start")
    private Integer evidenceStart;

    @Column(name = "evidence_end")
    private Integer evidenceEnd;

    @Column(name = "evidence_match", length = 32)
    private String evidenceMatch;

    private Double confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", length = 16, nullable = false)
    private ReviewStatus reviewStatus = ReviewStatus.PENDING_REVIEW;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", length = 16, nullable = false)
    private SyncStatus syncStatus = SyncStatus.NOT_SYNCED;

    /** Optimistic-lock counter; incremented by every persisted change (edit, approve, reject). */
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    // ---- Trello integration (all nullable until a sync happens) ----
    @Column(name = "trello_card_id", length = 64)
    private String trelloCardId;

    @Column(name = "trello_card_url", length = 512)
    private String trelloCardUrl;

    @Column(name = "trello_list_id", length = 64)
    private String trelloListId;

    @Column(name = "synced_at")
    private Instant syncedAt;

    @Column(name = "sync_attempted_at")
    private Instant syncAttemptedAt;

    @Column(name = "sync_error_code", length = 64)
    private String syncErrorCode;

    @Column(name = "sync_error_message", length = 500)
    private String syncErrorMessage;

    /** Task version that was approved when the card was created. */
    @Column(name = "synced_version")
    private Long syncedVersion;

    protected TaskProposal() {}

    public TaskProposal(Meeting meeting, int position, String title, String description, String owner,
                        String deadline, String evidenceQuote, EvidenceStatus evidenceStatus,
                        Integer evidenceStart, Integer evidenceEnd, String evidenceMatch, Double confidence) {
        this.meeting = meeting;
        this.position = position;
        this.title = title;
        this.description = description == null ? "" : description;
        this.owner = owner;
        this.deadline = deadline;
        this.evidenceQuote = evidenceQuote == null ? "" : evidenceQuote;
        this.evidenceStatus = evidenceStatus;
        this.evidenceStart = evidenceStart;
        this.evidenceEnd = evidenceEnd;
        this.evidenceMatch = evidenceMatch;
        this.confidence = confidence;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // Evidence fields intentionally have no setters: they can only be set at creation.
    public void setTitle(String title) { this.title = title; }
    public void setDescription(String description) { this.description = description; }
    public void setOwner(String owner) { this.owner = owner; }
    public void setDeadline(String deadline) { this.deadline = deadline; }

    public void markApproved() {
        this.reviewStatus = ReviewStatus.APPROVED;
        this.approvedAt = Instant.now();
        this.rejectedAt = null;
    }

    public void markRejected() {
        this.reviewStatus = ReviewStatus.REJECTED;
        this.rejectedAt = Instant.now();
        this.approvedAt = null;
    }

    public void markSynced(String cardId, String cardUrl, String listId, long approvedVersion) {
        this.syncStatus = SyncStatus.SYNCED;
        this.trelloCardId = cardId;
        this.trelloCardUrl = cardUrl;
        this.trelloListId = listId;
        this.syncedAt = Instant.now();
        this.syncedVersion = approvedVersion;
        this.syncErrorCode = null;
        this.syncErrorMessage = null;
    }

    public void markSyncFailed(String code, String message) {
        this.syncStatus = SyncStatus.FAILED;
        recordSyncError(code, message);
    }

    public void markSyncUncertain(String code, String message) {
        this.syncStatus = SyncStatus.UNCERTAIN;
        recordSyncError(code, message);
    }

    private void recordSyncError(String code, String message) {
        this.syncErrorCode = code;
        this.syncErrorMessage = message == null ? null : message.substring(0, Math.min(message.length(), 500));
    }

    /** An edit after a failed sync starts over: the old failure no longer describes this content. */
    public void clearSyncFailure() {
        if (this.syncStatus == SyncStatus.FAILED) {
            this.syncStatus = SyncStatus.NOT_SYNCED;
            this.syncErrorCode = null;
            this.syncErrorMessage = null;
        }
    }

    public void resetToPendingReview() {
        this.reviewStatus = ReviewStatus.PENDING_REVIEW;
        this.approvedAt = null;
        this.rejectedAt = null;
    }

    public String getId() { return id; }
    public Meeting getMeeting() { return meeting; }
    public int getPosition() { return position; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getOwner() { return owner; }
    public String getDeadline() { return deadline; }
    public String getEvidenceQuote() { return evidenceQuote; }
    public EvidenceStatus getEvidenceStatus() { return evidenceStatus; }
    public Integer getEvidenceStart() { return evidenceStart; }
    public Integer getEvidenceEnd() { return evidenceEnd; }
    public String getEvidenceMatch() { return evidenceMatch; }
    public Double getConfidence() { return confidence; }
    public ReviewStatus getReviewStatus() { return reviewStatus; }
    public SyncStatus getSyncStatus() { return syncStatus; }
    public long getVersion() { return version == null ? 0L : version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getRejectedAt() { return rejectedAt; }
    public String getTrelloCardId() { return trelloCardId; }
    public String getTrelloCardUrl() { return trelloCardUrl; }
    public String getTrelloListId() { return trelloListId; }
    public Instant getSyncedAt() { return syncedAt; }
    public Instant getSyncAttemptedAt() { return syncAttemptedAt; }
    public String getSyncErrorCode() { return syncErrorCode; }
    public String getSyncErrorMessage() { return syncErrorMessage; }
    public Long getSyncedVersion() { return syncedVersion; }
}
