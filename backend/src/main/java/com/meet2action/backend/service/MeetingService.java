package com.meet2action.backend.service;

import com.meet2action.backend.agent.AgentClient;
import com.meet2action.backend.agent.AgentException;
import com.meet2action.backend.agent.AgentExtractResponse;
import com.meet2action.backend.common.ApiException;
import com.meet2action.backend.domain.EvidenceStatus;
import com.meet2action.backend.domain.ExtractionStatus;
import com.meet2action.backend.domain.InsightType;
import com.meet2action.backend.domain.Meeting;
import com.meet2action.backend.domain.MeetingInsight;
import com.meet2action.backend.domain.MeetingInsightRepository;
import com.meet2action.backend.domain.MeetingRepository;
import com.meet2action.backend.domain.TaskProposal;
import com.meet2action.backend.domain.TaskProposalRepository;
import com.meet2action.backend.web.Dtos.InsightResponse;
import com.meet2action.backend.web.Dtos.MeetingResponse;
import com.meet2action.backend.web.Dtos.TaskResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MeetingService {

    private static final Logger log = LoggerFactory.getLogger(MeetingService.class);

    public record CreateResult(MeetingResponse meeting, boolean created) {}

    private final MeetingRepository meetings;
    private final TaskProposalRepository tasks;
    private final MeetingInsightRepository insights;
    private final AgentClient agent;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final Duration dedupeWindow;

    public MeetingService(MeetingRepository meetings, TaskProposalRepository tasks,
                          MeetingInsightRepository insights, AgentClient agent,
                          PlatformTransactionManager txManager,
                          @Value("${app.meetings.dedupe-window-seconds:30}") long dedupeSeconds) {
        this.meetings = meetings;
        this.tasks = tasks;
        this.insights = insights;
        this.agent = agent;
        this.tx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.dedupeWindow = Duration.ofSeconds(dedupeSeconds);
    }

    /**
     * Saves the meeting, calls the agent (outside any DB transaction), then stores the result.
     * The meeting row is committed first so a failed extraction is recorded, not lost.
     */
    public CreateResult create(String transcript) {
        String hash = sha256(transcript);

        if (!dedupeWindow.isZero()) {
            var existing = readTx.execute(s -> meetings
                    .findFirstByTranscriptHashAndExtractionStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
                            hash, EnumSet.of(ExtractionStatus.PENDING, ExtractionStatus.COMPLETED),
                            Instant.now().minus(dedupeWindow))
                    .map(this::toResponse).orElse(null));
            if (existing != null) {
                log.info("Duplicate transcript submission; returning existing meeting {}", existing.id());
                return new CreateResult(existing, false);
            }
        }

        String meetingId = tx.execute(s -> meetings.save(new Meeting(transcript, hash)).getId());

        AgentExtractResponse result;
        try {
            result = agent.extract(transcript);
        } catch (AgentException e) {
            markFailed(meetingId, e.getCode(), e.getMessage());
            throw extractionFailed(meetingId, e.getStatus(), e.getCode(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("Unexpected error during extraction for meeting {}", meetingId, e);
            markFailed(meetingId, "internal_error", "Unexpected error during extraction.");
            throw extractionFailed(meetingId, HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                    "Unexpected error during extraction.");
        }

        try {
            MeetingResponse response = tx.execute(s -> {
                Meeting meeting = meetings.findById(meetingId).orElseThrow();
                persistResult(meeting, result);
                meeting.markCompleted();
                meetings.save(meeting);
                return toResponse(meeting);
            });
            return new CreateResult(response, true);
        } catch (RuntimeException e) {
            // The whole result transaction rolled back, so no partial proposals exist.
            log.error("Failed to persist extraction result for meeting {}", meetingId, e);
            try {
                markFailed(meetingId, "persistence_failed", "Extraction succeeded but the result could not be stored.");
            } catch (RuntimeException inner) {
                log.error("Also failed to record the failure for meeting {}", meetingId, inner);
            }
            throw e;
        }
    }

    public MeetingResponse get(String meetingId) {
        return readTx.execute(s -> meetings.findById(meetingId).map(this::toResponse)
                .orElseThrow(() -> ApiException.notFound("Meeting", meetingId)));
    }

    private void persistResult(Meeting meeting, AgentExtractResponse r) {
        List<TaskProposal> toSave = new ArrayList<>();
        int pos = 0;
        for (AgentExtractResponse.Task t : r.tasks()) {
            toSave.add(new TaskProposal(meeting, pos++, t.title().trim(), t.description(),
                    blankToNull(t.owner()), blankToNull(t.deadline()), t.evidenceQuote(),
                    evidenceStatus(t.evidenceStatus()), t.evidenceStart(), t.evidenceEnd(),
                    t.evidenceMatch(), t.confidence()));
        }
        tasks.saveAll(toSave);

        List<MeetingInsight> ins = new ArrayList<>();
        addInsights(ins, meeting, InsightType.DECISION, r.decisions());
        addInsights(ins, meeting, InsightType.UNRESOLVED_QUESTION, r.unresolvedQuestions());
        addInsights(ins, meeting, InsightType.COMPLETED_UPDATE, r.completedUpdates());
        insights.saveAll(ins);
    }

    private void addInsights(List<MeetingInsight> out, Meeting meeting, InsightType type,
                             List<AgentExtractResponse.Statement> statements) {
        int pos = 0;
        for (AgentExtractResponse.Statement s : statements) {
            out.add(new MeetingInsight(meeting, type, pos++, s.text(), blankToNull(s.owner()), s.evidenceQuote(),
                    evidenceStatus(s.evidenceStatus()), s.evidenceStart(), s.evidenceEnd(), s.evidenceMatch()));
        }
    }

    private void markFailed(String meetingId, String code, String message) {
        tx.executeWithoutResult(s -> meetings.findById(meetingId).ifPresent(m -> {
            m.markFailed(code, message);
            meetings.save(m);
        }));
    }

    private ApiException extractionFailed(String meetingId, HttpStatus status, String code, String message) {
        return new ApiException(status, code, message,
                java.util.Map.of("meeting_id", meetingId, "extraction_status", ExtractionStatus.FAILED.name()));
    }

    /** Must run inside a transaction (lazy associations are read via the repositories). */
    private MeetingResponse toResponse(Meeting m) {
        List<TaskResponse> taskDtos = tasks.findByMeetingIdOrderByPositionAsc(m.getId()).stream()
                .map(TaskResponse::from).toList();
        List<MeetingInsight> all = insights.findByMeetingIdOrderByTypeAscPositionAsc(m.getId());
        Function<InsightType, List<InsightResponse>> byType = type -> all.stream()
                .filter(i -> i.getType() == type).map(InsightResponse::from).toList();
        return MeetingResponse.of(m, taskDtos, byType.apply(InsightType.DECISION),
                byType.apply(InsightType.UNRESOLVED_QUESTION), byType.apply(InsightType.COMPLETED_UPDATE));
    }

    private static EvidenceStatus evidenceStatus(String s) {
        return EvidenceStatus.valueOf(s.toUpperCase(Locale.ROOT));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
