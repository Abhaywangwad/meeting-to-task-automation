package com.meet2action.backend.web;

import com.meet2action.backend.domain.EvidenceStatus;
import com.meet2action.backend.domain.ExtractionStatus;
import com.meet2action.backend.domain.Meeting;
import com.meet2action.backend.domain.MeetingInsight;
import com.meet2action.backend.domain.ReviewStatus;
import com.meet2action.backend.domain.SyncStatus;
import com.meet2action.backend.domain.TaskProposal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Request/response DTOs. JSON field names are snake_case via the global Jackson setting. */
public final class Dtos {

    private Dtos() {}

    /** Same limit as the agent service (MAX_TRANSCRIPT_CHARS = 200000). */
    public static final int MAX_TRANSCRIPT_CHARS = 200_000;

    public record CreateMeetingRequest(
            @NotBlank(message = "must not be blank")
            @Size(max = MAX_TRANSCRIPT_CHARS, message = "must be at most 200000 characters")
            String transcript) {}

    /**
     * Only these fields are editable. Evidence fields are not part of this DTO, and the
     * server rejects unknown JSON properties, so they cannot be set by a client.
     * Absent field = unchanged. For owner/deadline/description, an empty string clears the value.
     */
    public record UpdateTaskRequest(
            @Size(max = 500) String title,
            String description,
            @Size(max = 255) String owner,
            @Size(max = 255) String deadline,
            Long version) {}

    public record ApproveRequest(@NotNull(message = "is required") Long version) {}

    public record RejectRequest(Long version) {}

    public record SyncRequest(@NotNull(message = "is required") Long version) {}

    /** Only whether Trello is configured; never any credential or identifier. */
    public record TrelloStatusResponse(boolean configured) {}

    public record TaskResponse(
            String id,
            String meetingId,
            String title,
            String description,
            String owner,
            String deadline,
            String evidenceQuote,
            EvidenceStatus evidenceStatus,
            boolean evidenceVerified,
            Integer evidenceStart,
            Integer evidenceEnd,
            String evidenceMatch,
            Double confidence,
            ReviewStatus reviewStatus,
            SyncStatus syncStatus,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Instant approvedAt,
            Instant rejectedAt,
            String trelloCardId,
            String trelloCardUrl,
            Instant syncedAt,
            Instant syncAttemptedAt,
            String syncErrorCode,
            String syncErrorMessage) {

        public static TaskResponse from(TaskProposal t) {
            return new TaskResponse(t.getId(), t.getMeeting().getId(), t.getTitle(), t.getDescription(),
                    t.getOwner(), t.getDeadline(), t.getEvidenceQuote(), t.getEvidenceStatus(),
                    t.getEvidenceStatus() == EvidenceStatus.VERIFIED, t.getEvidenceStart(), t.getEvidenceEnd(),
                    t.getEvidenceMatch(), t.getConfidence(), t.getReviewStatus(), t.getSyncStatus(),
                    t.getVersion(), t.getCreatedAt(), t.getUpdatedAt(), t.getApprovedAt(), t.getRejectedAt(),
                    t.getTrelloCardId(), t.getTrelloCardUrl(), t.getSyncedAt(), t.getSyncAttemptedAt(),
                    t.getSyncErrorCode(), t.getSyncErrorMessage());
        }
    }

    public record InsightResponse(
            String id,
            String text,
            String owner,
            String evidenceQuote,
            EvidenceStatus evidenceStatus,
            boolean evidenceVerified,
            Integer evidenceStart,
            Integer evidenceEnd,
            String evidenceMatch) {

        public static InsightResponse from(MeetingInsight i) {
            return new InsightResponse(i.getId(), i.getText(), i.getOwner(), i.getEvidenceQuote(),
                    i.getEvidenceStatus(), i.getEvidenceStatus() == EvidenceStatus.VERIFIED,
                    i.getEvidenceStart(), i.getEvidenceEnd(), i.getEvidenceMatch());
        }
    }

    public record MeetingResponse(
            String id,
            String transcript,
            Instant createdAt,
            ExtractionStatus extractionStatus,
            String errorCode,
            String errorMessage,
            List<TaskResponse> tasks,
            List<InsightResponse> decisions,
            List<InsightResponse> unresolvedQuestions,
            List<InsightResponse> completedUpdates) {

        public static MeetingResponse of(Meeting m, List<TaskResponse> tasks, List<InsightResponse> decisions,
                                         List<InsightResponse> questions, List<InsightResponse> updates) {
            return new MeetingResponse(m.getId(), m.getTranscript(), m.getCreatedAt(), m.getExtractionStatus(),
                    m.getErrorCode(), m.getErrorMessage(), tasks, decisions, questions, updates);
        }
    }
}
