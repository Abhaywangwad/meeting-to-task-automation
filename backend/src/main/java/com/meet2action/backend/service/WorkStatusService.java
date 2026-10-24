package com.meet2action.backend.service;

import com.meet2action.backend.common.ApiException;
import com.meet2action.backend.domain.ReviewStatus;
import com.meet2action.backend.domain.SyncStatus;
import com.meet2action.backend.domain.TaskProposal;
import com.meet2action.backend.domain.TaskProposalRepository;
import com.meet2action.backend.domain.WorkStatus;
import com.meet2action.backend.trello.TrelloClient;
import com.meet2action.backend.trello.TrelloException;
import com.meet2action.backend.trello.TrelloProperties;
import com.meet2action.backend.web.Dtos.TaskResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit work-status changes (To Do / In Progress / Completed) that move the task's EXISTING Trello card.
 *
 * <p>Rules: the task must be approved and already synced (it needs a saved card id; this class can never create
 * a card); the request must carry the current version; the change is only recorded after Trello confirms the move.
 * Concurrent requests are serialised by one conditional UPDATE (claimForMove) and no database transaction is held
 * open during the Trello call. Moving a card to a list it is already in is harmless, so a failed or uncertain move
 * simply leaves the work status unchanged and may be retried.
 */
@Service
public class WorkStatusService {

    private static final Logger log = LoggerFactory.getLogger(WorkStatusService.class);

    private final TaskProposalRepository tasks;
    private final TrelloClient trello;
    private final TrelloProperties props;
    private final TransactionTemplate tx;

    public WorkStatusService(TaskProposalRepository tasks, TrelloClient trello, TrelloProperties props,
                             PlatformTransactionManager txManager) {
        this.tasks = tasks;
        this.trello = trello;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    public TaskResponse change(String taskId, WorkStatus target, long version) {
        TaskProposal t = load(taskId);

        if (t.getReviewStatus() != ReviewStatus.APPROVED) {
            throw ApiException.conflict("task_not_approved",
                    "Only an approved task can change work status (current review status: " + t.getReviewStatus() + ").");
        }
        if (t.getSyncStatus() != SyncStatus.SYNCED || t.getTrelloCardId() == null) {
            throw ApiException.conflict("not_synced",
                    "This task has no Trello card yet. Sync it to Trello first; a card can only be moved after it exists.");
        }
        if (t.getWorkStatus() == target) {
            return view(taskId); // already there: nothing to do, and no Trello request
        }
        if (t.getVersion() != version) {
            throw ApiException.conflict("version_conflict", "Task version is " + t.getVersion()
                    + " but the request used " + version + "; reload the task and retry.");
        }
        String listId = props.listFor(target);
        if (!props.isConfigured() || listId.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "trello_list_not_configured",
                    "The Trello list for " + target + " is not configured. Set "
                            + TrelloProperties.variableFor(target) + " (and the other work lists) and restart the backend.",
                    Map.of("task_id", taskId));
        }

        Instant now = Instant.now();
        Integer claimed = tx.execute(s -> tasks.claimForMove(taskId, version, target, now,
                now.minus(Duration.ofSeconds(props.getStaleSyncSeconds()))));
        if (claimed == null || claimed != 1) {
            TaskProposal cur = load(taskId);
            if (cur.getWorkStatus() == target) {
                return view(taskId);
            }
            if (cur.getWorkMoveTarget() != null) {
                throw ApiException.conflict("work_move_in_progress", "Another work-status change for this task is in progress.");
            }
            throw ApiException.conflict("version_conflict", "The task changed before the move could start; reload it and retry.");
        }

        log.info("Moving task {} card to {}", taskId, target);
        try {
            trello.moveCard(t.getTrelloCardId(), listId);
            persist(taskId, task -> task.finishWorkMove(target));
            return view(taskId);
        } catch (TrelloException e) {
            persist(taskId, TaskProposal::abandonWorkMove);
            throw failure(taskId, e);
        } catch (RuntimeException e) {
            log.error("Unexpected error moving the card for task {}", taskId, e);
            persist(taskId, TaskProposal::abandonWorkMove);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "work_move_failed",
                    "The card could not be moved. The work status was not changed.", Map.of("task_id", taskId));
        }
    }

    private ApiException failure(String taskId, TrelloException e) {
        Map<String, Object> extra = Map.of("task_id", taskId);
        if (e.cardMayExist()) { // timeout / 5xx / unreadable reply: the move may or may not have happened
            return new ApiException(e.getKind() == TrelloException.Kind.TIMEOUT ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY,
                    "work_move_uncertain",
                    "Trello did not confirm the move, so it may or may not have happened. The work status in Meet2Action "
                            + "was NOT changed. Check the board, or try again (moving a card is safe to repeat).", extra);
        }
        return switch (e.getKind()) {
            case AUTH -> new ApiException(HttpStatus.BAD_GATEWAY, "trello_auth_failed",
                    "Trello rejected the API key or token. The card was not moved.", extra);
            case LIST_INVALID -> new ApiException(HttpStatus.BAD_GATEWAY, "trello_card_or_list_not_found",
                    "Trello could not find the card or the destination list (it may have been deleted or the ID is wrong). "
                            + "The card was not moved.", extra);
            case RATE_LIMITED -> new ApiException(HttpStatus.TOO_MANY_REQUESTS, "trello_rate_limited",
                    "Trello is rate limiting requests. The card was not moved; try again shortly.", extra);
            case UNREACHABLE -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "trello_unreachable",
                    "Trello could not be reached. The card was not moved.", extra);
            default -> new ApiException(HttpStatus.BAD_GATEWAY, "trello_rejected",
                    "Trello rejected the request. The card was not moved.", extra);
        };
    }

    private void persist(String taskId, java.util.function.Consumer<TaskProposal> change) {
        tx.executeWithoutResult(s -> {
            TaskProposal t = tasks.findById(taskId).orElseThrow();
            change.accept(t);
            tasks.saveAndFlush(t);
        });
    }

    private TaskProposal load(String taskId) {
        return tx.execute(s -> {
            TaskProposal t = tasks.findById(taskId).orElseThrow(() -> ApiException.notFound("Task", taskId));
            t.getMeeting().getId();
            return t;
        });
    }

    private TaskResponse view(String taskId) {
        return tx.execute(s -> TaskResponse.from(tasks.findById(taskId).orElseThrow()));
    }
}
