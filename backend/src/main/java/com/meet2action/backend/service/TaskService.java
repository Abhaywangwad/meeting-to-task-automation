package com.meet2action.backend.service;

import com.meet2action.backend.common.ApiException;
import com.meet2action.backend.domain.EvidenceStatus;
import com.meet2action.backend.domain.ReviewStatus;
import com.meet2action.backend.domain.TaskProposal;
import com.meet2action.backend.domain.TaskProposalRepository;
import com.meet2action.backend.web.Dtos.TaskResponse;
import com.meet2action.backend.web.Dtos.UpdateTaskRequest;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Review state machine (enforced here, regardless of what clients send):
 *
 * <pre>
 *   PENDING_REVIEW --approve (evidence VERIFIED, version matches)--> APPROVED
 *   PENDING_REVIEW --reject-------------------------------------------> REJECTED
 *   APPROVED --edit of title/description/owner/deadline--> PENDING_REVIEW (approval invalidated)
 *   APPROVED --reject--> REJECTED
 *   REJECTED is terminal: it cannot be approved, edited or re-rejected.
 * </pre>
 *
 * Evidence status is never changed here. Nothing in this class talks to an external system;
 * syncStatus stays NOT_SYNCED. A VERIFIED quote shows only that the words occur in the
 * transcript, not that the proposal is semantically correct, so approval is a human decision.
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskProposalRepository tasks;

    public TaskService(TaskProposalRepository tasks) {
        this.tasks = tasks;
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> list(String meetingId, ReviewStatus reviewStatus) {
        List<TaskProposal> found;
        if (meetingId != null && reviewStatus != null) {
            found = tasks.findByMeetingIdAndReviewStatusOrderByPositionAsc(meetingId, reviewStatus);
        } else if (meetingId != null) {
            found = tasks.findByMeetingIdOrderByPositionAsc(meetingId);
        } else if (reviewStatus != null) {
            found = tasks.findByReviewStatusOrderByCreatedAtAscPositionAsc(reviewStatus);
        } else {
            found = tasks.findAllByOrderByCreatedAtAscPositionAsc();
        }
        return found.stream().map(TaskResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public TaskResponse get(String taskId) {
        return TaskResponse.from(find(taskId));
    }

    @Transactional
    public TaskResponse edit(String taskId, UpdateTaskRequest req) {
        TaskProposal t = find(taskId);
        checkVersionIfGiven(t, req.version());
        ensureNotLockedBySync(t);
        if (t.getReviewStatus() == ReviewStatus.REJECTED) {
            throw ApiException.conflict("task_rejected", "A rejected task cannot be edited.");
        }

        boolean changed = false;
        if (req.title() != null) {
            String title = req.title().trim();
            if (title.isEmpty()) {
                throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "validation_error",
                        "Invalid request: title must not be blank");
            }
            if (!title.equals(t.getTitle())) {
                t.setTitle(title);
                changed = true;
            }
        }
        if (req.description() != null) {
            String d = req.description().trim();
            if (!d.equals(t.getDescription())) {
                t.setDescription(d);
                changed = true;
            }
        }
        if (req.owner() != null) {
            String o = emptyToNull(req.owner());
            if (!Objects.equals(o, t.getOwner())) {
                t.setOwner(o);
                changed = true;
            }
        }
        if (req.deadline() != null) {
            String d = emptyToNull(req.deadline());
            if (!Objects.equals(d, t.getDeadline())) {
                t.setDeadline(d);
                changed = true;
            }
        }

        if (!changed) {
            return TaskResponse.from(t);
        }
        t.clearSyncFailure();
        if (t.getReviewStatus() == ReviewStatus.APPROVED) {
            // An approval applies to one exact version of the task; any actionable change voids it.
            t.resetToPendingReview();
            log.info("Task {} edited after approval; reset to PENDING_REVIEW", t.getId());
        }
        return TaskResponse.from(tasks.saveAndFlush(t));
    }

    @Transactional
    public TaskResponse approve(String taskId, long expectedVersion) {
        TaskProposal t = find(taskId);
        if (t.getVersion() != expectedVersion) {
            throw staleVersion(t, expectedVersion);
        }
        if (t.getReviewStatus() != ReviewStatus.PENDING_REVIEW) {
            throw ApiException.conflict("invalid_state",
                    "Only a task in PENDING_REVIEW can be approved (current: " + t.getReviewStatus() + ").");
        }
        if (t.getEvidenceStatus() != EvidenceStatus.VERIFIED) {
            throw ApiException.unprocessable("evidence_not_verified",
                    "A task whose evidence quotation is not verified against the transcript cannot be approved.");
        }
        t.markApproved();
        log.info("Task {} approved at version {}", t.getId(), expectedVersion);
        return TaskResponse.from(tasks.saveAndFlush(t));
    }

    @Transactional
    public TaskResponse reject(String taskId, Long expectedVersion) {
        TaskProposal t = find(taskId);
        checkVersionIfGiven(t, expectedVersion);
        ensureNotLockedBySync(t);
        if (t.getReviewStatus() == ReviewStatus.REJECTED) {
            throw ApiException.conflict("invalid_state", "The task is already rejected.");
        }
        t.markRejected();
        log.info("Task {} rejected", t.getId());
        return TaskResponse.from(tasks.saveAndFlush(t));
    }

    /** Edits/rejection are blocked while a card is being created, may exist, or does exist. */
    private void ensureNotLockedBySync(TaskProposal t) {
        switch (t.getSyncStatus()) {
            case SYNCING -> throw ApiException.conflict("sync_in_progress",
                    "The task is being synced to Trello; wait for it to finish.");
            case UNCERTAIN -> throw ApiException.conflict("sync_uncertain",
                    "A Trello card may already exist for this task; check Trello status first.");
            case SYNCED -> throw ApiException.conflict("already_synced",
                    "The task is already synced to Trello and can no longer be changed here.");
            default -> { }
        }
    }

    private TaskProposal find(String taskId) {
        return tasks.findById(taskId).orElseThrow(() -> ApiException.notFound("Task", taskId));
    }

    private void checkVersionIfGiven(TaskProposal t, Long expected) {
        if (expected != null && t.getVersion() != expected) {
            throw staleVersion(t, expected);
        }
    }

    private ApiException staleVersion(TaskProposal t, long expected) {
        return ApiException.conflict("version_conflict", "Task version is " + t.getVersion()
                + " but the request used " + expected + "; reload the task and retry.");
    }

    private static String emptyToNull(String s) {
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
