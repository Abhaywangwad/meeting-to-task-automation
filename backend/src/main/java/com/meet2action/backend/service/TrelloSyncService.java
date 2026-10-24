package com.meet2action.backend.service;

import com.meet2action.backend.common.ApiException;
import com.meet2action.backend.domain.EvidenceStatus;
import com.meet2action.backend.domain.ReviewStatus;
import com.meet2action.backend.domain.SyncStatus;
import com.meet2action.backend.domain.TaskProposal;
import com.meet2action.backend.domain.TaskProposalRepository;
import com.meet2action.backend.trello.TrelloCardContent;
import com.meet2action.backend.trello.TrelloClient;
import com.meet2action.backend.trello.TrelloException;
import com.meet2action.backend.trello.TrelloProperties;
import com.meet2action.backend.web.Dtos.TaskResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit, user-triggered Trello sync. The server is the only authority:
 * <ol>
 *   <li>validate (exists, APPROVED, evidence VERIFIED, version, not syncing/uncertain, Trello configured);</li>
 *   <li>claim the single active attempt with one conditional UPDATE (short transaction);</li>
 *   <li>call Trello with NO database transaction open;</li>
 *   <li>record the outcome in a second short transaction.</li>
 * </ol>
 * Trello offers no idempotency key, so exactly-once creation is impossible. Instead: a create is never
 * retried automatically, every card carries an exact marker, and any ambiguous outcome (timeout, 5xx,
 * unreadable reply) parks the task in UNCERTAIN until {@link #reconcile} looks for that marker.
 * Remaining gaps: a card that a human moved off the list or archived cannot be found by reconciliation,
 * and a process crash between Trello's reply and our final write leaves SYNCING (recoverable by reconcile
 * once the attempt is stale).
 */
@Service
public class TrelloSyncService {

    private static final Logger log = LoggerFactory.getLogger(TrelloSyncService.class);

    private final TaskProposalRepository tasks;
    private final TrelloClient trello;
    private final TrelloProperties props;
    private final TransactionTemplate tx;

    public TrelloSyncService(TaskProposalRepository tasks, TrelloClient trello, TrelloProperties props,
                             PlatformTransactionManager txManager) {
        this.tasks = tasks;
        this.trello = trello;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    public boolean isConfigured() {
        return props.isConfigured();
    }

    public TaskResponse sync(String taskId, long version) {
        TaskProposal t = load(taskId);

        if (t.getSyncStatus() == SyncStatus.SYNCED) {
            return view(taskId); // idempotent: return the stored result, never a second card
        }
        if (t.getReviewStatus() != ReviewStatus.APPROVED) {
            throw ApiException.conflict("task_not_approved",
                    "Only an approved task can be synced (current review status: " + t.getReviewStatus() + ").");
        }
        if (t.getEvidenceStatus() != EvidenceStatus.VERIFIED) {
            throw ApiException.unprocessable("evidence_not_verified",
                    "A task whose evidence is not verified cannot be synced.");
        }
        if (t.getSyncStatus() == SyncStatus.SYNCING) {
            throw ApiException.conflict("sync_in_progress", "A sync for this task is already in progress.");
        }
        if (t.getSyncStatus() == SyncStatus.UNCERTAIN) {
            throw ApiException.conflict("sync_uncertain", "A previous sync attempt has an unknown outcome and a card "
                    + "may already exist. Check Trello status (reconcile) before syncing again.");
        }
        if (t.getVersion() != version) {
            throw ApiException.conflict("version_conflict", "Task version is " + t.getVersion()
                    + " but the request used " + version + "; reload the task and retry.");
        }
        if (!props.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "trello_not_configured",
                    "Trello is not configured on the server. Set TRELLO_API_KEY, TRELLO_TOKEN and TRELLO_LIST_ID "
                            + "and restart the backend.", Map.of("task_id", taskId));
        }

        // Atomic claim: only one concurrent caller can win this conditional update.
        Integer claimed = tx.execute(s -> tasks.claimForSync(taskId, version, Instant.now()));
        if (claimed == null || claimed != 1) {
            TaskProposal now = load(taskId);
            if (now.getSyncStatus() == SyncStatus.SYNCED) {
                return view(taskId);
            }
            if (now.getSyncStatus() == SyncStatus.SYNCING) {
                throw ApiException.conflict("sync_in_progress", "A sync for this task is already in progress.");
            }
            throw ApiException.conflict("version_conflict",
                    "The task changed before the sync could start; reload it and retry.");
        }

        // Content is built from the task as it was claimed (edits are blocked while SYNCING).
        TaskProposal claimedTask = load(taskId);
        TrelloCardContent.Content card = TrelloCardContent.build(claimedTask);
        long approvedVersion = claimedTask.getVersion();
        log.info("Creating Trello card for task {}", taskId);

        try {
            TrelloClient.Card created = trello.createCard(props.getListId(), card.name(), card.description(), card.dueIso());
            persist(taskId, t2 -> t2.markSynced(created.id(), created.url(), props.getListId(), approvedVersion));
            log.info("Task {} synced to Trello card {}", taskId, created.id());
            return view(taskId);
        } catch (TrelloException e) {
            throw recordFailure(taskId, e);
        } catch (RuntimeException e) {
            // We cannot tell whether the card was created: be conservative.
            log.error("Unexpected error while syncing task {}", taskId, e);
            persist(taskId, t2 -> t2.markSyncUncertain("sync_uncertain",
                    "An unexpected error happened during the sync; a card may exist. Check Trello status."));
            throw uncertain(taskId, HttpStatus.BAD_GATEWAY, "An unexpected error happened during the sync.");
        }
    }

    /**
     * Looks for a card carrying this task's marker. Found: SYNCED with the real card. Not found on the list:
     * FAILED (no card seen; a manually moved/archived card would be missed). Search itself fails: UNCERTAIN.
     */
    public TaskResponse reconcile(String taskId) {
        TaskProposal t = load(taskId);
        if (t.getSyncStatus() == SyncStatus.SYNCED) {
            return view(taskId);
        }
        if (t.getSyncStatus() != SyncStatus.UNCERTAIN && t.getSyncStatus() != SyncStatus.SYNCING) {
            throw ApiException.conflict("nothing_to_reconcile",
                    "Only a task with an uncertain (or stuck) sync can be reconciled.");
        }
        if (!props.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "trello_not_configured",
                    "Trello is not configured on the server.", Map.of("task_id", taskId));
        }
        Instant now = Instant.now();
        Integer claimed = tx.execute(s -> tasks.claimForReconcile(taskId, now,
                now.minus(Duration.ofSeconds(props.getStaleSyncSeconds()))));
        if (claimed == null || claimed != 1) {
            throw ApiException.conflict("sync_in_progress", "A sync or check for this task is still in progress.");
        }

        long approvedVersion = load(taskId).getVersion();
        String marker = TrelloCardContent.marker(taskId);
        try {
            Optional<TrelloClient.Card> found = trello.findCardByMarker(props.getListId(), marker);
            if (found.isPresent()) {
                TrelloClient.Card c = found.get();
                persist(taskId, t2 -> t2.markSynced(c.id(), c.url(), props.getListId(), approvedVersion));
                log.info("Reconciled task {} with existing Trello card {}", taskId, c.id());
            } else {
                persist(taskId, t2 -> t2.markSyncFailed("card_not_found",
                        "No card with this task's marker was found on the list, so a card was most likely not "
                                + "created. You can sync again. If you moved or archived a card manually, "
                                + "check Trello first."));
                log.info("Reconciliation found no card for task {}", taskId);
            }
            return view(taskId);
        } catch (RuntimeException e) {
            log.warn("Reconciliation for task {} could not complete: {}", taskId, e.getClass().getSimpleName());
            persist(taskId, t2 -> t2.markSyncUncertain("sync_uncertain",
                    "Trello could not be checked, so the outcome is still unknown. Try again later."));
            throw uncertain(taskId, HttpStatus.BAD_GATEWAY, "Trello could not be checked; the outcome is still unknown.");
        }
    }

    // ---------- helpers ----------

    private ApiException recordFailure(String taskId, TrelloException e) {
        if (e.cardMayExist()) {
            persist(taskId, t -> t.markSyncUncertain("sync_uncertain", e.getMessage()
                    + " A card may or may not exist; check Trello status before syncing again."));
            return uncertain(taskId, e.getKind() == TrelloException.Kind.TIMEOUT
                    ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY, e.getMessage());
        }
        HttpStatus status;
        String code;
        String message;
        switch (e.getKind()) {
            case AUTH -> {
                status = HttpStatus.BAD_GATEWAY;
                code = "trello_auth_failed";
                message = "Trello rejected the API key or token. Check TRELLO_API_KEY and TRELLO_TOKEN.";
            }
            case LIST_INVALID -> {
                status = HttpStatus.BAD_GATEWAY;
                code = "trello_list_invalid";
                message = "Trello could not use the destination list. Check TRELLO_LIST_ID and that the token can access its board.";
            }
            case RATE_LIMITED -> {
                status = HttpStatus.TOO_MANY_REQUESTS;
                code = "trello_rate_limited";
                message = "Trello is rate limiting requests. No card was created; try again shortly.";
            }
            case UNREACHABLE -> {
                status = HttpStatus.SERVICE_UNAVAILABLE;
                code = "trello_unreachable";
                message = "Trello could not be reached. No card was created.";
            }
            default -> {
                status = HttpStatus.BAD_GATEWAY;
                code = "trello_rejected";
                message = "Trello rejected the request. No card was created.";
            }
        }
        persist(taskId, t -> t.markSyncFailed(code, message));
        return new ApiException(status, code, message,
                Map.of("task_id", taskId, "sync_status", SyncStatus.FAILED.name()));
    }

    private ApiException uncertain(String taskId, HttpStatus status, String reason) {
        return new ApiException(status, "sync_uncertain",
                reason + " A card may or may not have been created. Use \"Check Trello\" (reconcile) to look for it; "
                        + "do not sync again until that is resolved.",
                Map.of("task_id", taskId, "sync_status", SyncStatus.UNCERTAIN.name()));
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
            t.getMeeting().getId(); // initialize what callers read outside the transaction
            return t;
        });
    }

    private TaskResponse view(String taskId) {
        return tx.execute(s -> TaskResponse.from(tasks.findById(taskId).orElseThrow()));
    }
}
