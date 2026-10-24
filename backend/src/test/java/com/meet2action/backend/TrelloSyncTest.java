package com.meet2action.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.meet2action.backend.agent.AgentClient;
import com.meet2action.backend.agent.AgentExtractResponse;
import com.meet2action.backend.agent.AgentExtractResponse.Task;
import com.meet2action.backend.domain.MeetingInsightRepository;
import com.meet2action.backend.domain.MeetingRepository;
import com.meet2action.backend.domain.ReviewStatus;
import com.meet2action.backend.domain.SyncStatus;
import com.meet2action.backend.domain.TaskProposal;
import com.meet2action.backend.domain.TaskProposalRepository;
import com.meet2action.backend.trello.TrelloClient;
import com.meet2action.backend.trello.TrelloException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Sync workflow through HTTP + service + JPA on in-memory H2. Trello and the agent are MOCKS: no network,
 * no credentials, and no real card can be created. (H2 is not MySQL; see MySqlIntegrationTest.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.trello.api-key=test-key-SECRET", "app.trello.token=test-token-SECRET", "app.trello.list-id=LIST1"})
class TrelloSyncTest {

    private static final String CARD_URL = "https://trello.com/c/AbCd1234/1-prepare-test-report";

    @Autowired MockMvc mvc;
    @Autowired MeetingRepository meetings;
    @Autowired TaskProposalRepository tasks;
    @Autowired MeetingInsightRepository insights;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @MockitoBean AgentClient agent;
    @MockitoBean TrelloClient trello;

    @BeforeEach
    void clean() {
        insights.deleteAll();
        tasks.deleteAll();
        meetings.deleteAll();
    }

    // ---------- helpers ----------
    private static Task verified(String title, String owner, String deadline) {
        int start = TestData.TRANSCRIPT.indexOf(TestData.QUOTE);
        return new Task("a", title, "Details of " + title, owner, deadline, TestData.QUOTE, true, "verified",
                start, start + TestData.QUOTE.length(), "exact", 0.9);
    }

    /** Creates a meeting whose first task is the given one (second is the unverified standard task). */
    private String[] createTasks(Task first) throws Exception {
        when(agent.extract(anyString())).thenReturn(new AgentExtractResponse(
                List.of(first, TestData.unverifiedTask()), List.of(), List.of(), List.of()));
        String json = mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("transcript", TestData.TRANSCRIPT))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new String[]{JsonPath.read(json, "$.tasks[0].id"), JsonPath.read(json, "$.tasks[1].id")};
    }

    private String approvedTask() throws Exception {
        return approvedTask(verified("Prepare test report", "Priya", "Friday"));
    }

    private String approvedTask(Task t) throws Exception {
        String id = createTasks(t)[0];
        mvc.perform(post("/api/tasks/" + id + "/approve").contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk());
        return id;
    }

    private ResultActions sync(String id, long version) throws Exception {
        return mvc.perform(post("/api/tasks/" + id + "/sync").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + "}"));
    }

    private ResultActions reconcile(String id) throws Exception {
        return mvc.perform(post("/api/tasks/" + id + "/sync/reconcile"));
    }

    private TaskProposal reload(String id) {
        return tasks.findById(id).orElseThrow();
    }

    private void cardCreated() {
        // doReturn: safe to call when the mock was previously stubbed to throw.
        org.mockito.Mockito.doReturn(new TrelloClient.Card("card123", CARD_URL))
                .when(trello).createCard(anyString(), anyString(), anyString(), any());
    }

    // ---------- 1, 2, 15: success, request content, persistence ----------
    @Test
    void approvedVerifiedTaskCreatesCardAndPersistsRealCardIdAndUrl() throws Exception {
        String id = approvedTask();
        cardCreated();

        String body = sync(id, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.sync_status").value("SYNCED"))
                .andExpect(jsonPath("$.trello_card_id").value("card123"))
                .andExpect(jsonPath("$.trello_card_url").value(CARD_URL))
                .andExpect(jsonPath("$.synced_at").isNotEmpty())
                .andExpect(jsonPath("$.review_status").value("APPROVED"))
                .andReturn().getResponse().getContentAsString();

        ArgumentCaptor<String> desc = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> due = ArgumentCaptor.forClass(String.class);
        verify(trello, times(1)).createCard(eq("LIST1"), eq("Prepare test report"), desc.capture(), due.capture());
        assertThat(desc.getValue()).contains("Details of Prepare test report", "**Owner:** Priya", "**Deadline:** Friday",
                "**Meeting evidence**", "> " + TestData.QUOTE, "[meet2action-task-id:" + id + "]");
        assertThat(due.getValue()).as("\"Friday\" is ambiguous, so no due date").isNull();

        TaskProposal t = reload(id);
        assertThat(t.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
        assertThat(t.getTrelloCardId()).isEqualTo("card123");
        assertThat(t.getTrelloCardUrl()).isEqualTo(CARD_URL);
        assertThat(t.getTrelloListId()).isEqualTo("LIST1");
        assertThat(t.getSyncedAt()).isNotNull();
        assertThat(t.getSyncedVersion()).isEqualTo(2L); // approved at v1, claimed -> v2
        assertThat(body).doesNotContain("SECRET");
    }

    @Test
    void explicitIsoDeadlineBecomesDueDate() throws Exception {
        String id = approvedTask(verified("Ship it", "Priya", "2030-01-15"));
        cardCreated();
        sync(id, 1).andExpect(status().isOk());
        verify(trello).createCard(eq("LIST1"), eq("Ship it"), anyString(), eq("2030-01-15T12:00:00.000Z"));
    }

    // ---------- 3: nothing invented ----------
    @Test
    void missingOwnerAndDeadlineAreNotInventedInTheCard() throws Exception {
        String id = approvedTask(verified("Anonymous task", null, null));
        cardCreated();
        sync(id, 1).andExpect(status().isOk());
        ArgumentCaptor<String> desc = ArgumentCaptor.forClass(String.class);
        verify(trello).createCard(eq("LIST1"), eq("Anonymous task"), desc.capture(), isNull());
        assertThat(desc.getValue()).doesNotContain("Owner:", "Deadline:");
    }

    // ---------- 4, 5, 6, 7: gates ----------
    @Test
    void pendingTaskCannotBeSynced() throws Exception {
        String id = createTasks(verified("T", "A", null))[0];
        sync(id, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("task_not_approved"));
        verifyNoInteractions(trello);
        assertThat(reload(id).getSyncStatus()).isEqualTo(SyncStatus.NOT_SYNCED);
    }

    @Test
    void rejectedTaskCannotBeSynced() throws Exception {
        String id = createTasks(verified("T", "A", null))[0];
        mvc.perform(post("/api/tasks/" + id + "/reject")).andExpect(status().isOk());
        sync(id, reload(id).getVersion()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("task_not_approved"));
        verifyNoInteractions(trello);
    }

    @Test
    void editedButNotReapprovedTaskCannotBeSynced() throws Exception {
        String id = approvedTask();
        mvc.perform(patch("/api/tasks/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"owner\":\"Sam\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.review_status").value("PENDING_REVIEW"));
        sync(id, reload(id).getVersion()).andExpect(status().isConflict());
        verifyNoInteractions(trello);
    }

    @Test
    void unverifiedEvidenceCannotBeSyncedEvenIfStateWasTamperedWith() throws Exception {
        String unverifiedId = createTasks(verified("T", "A", null))[1];
        // Simulate a corrupted row: approved although the evidence is unverified.
        jdbc.update("update task_proposals set review_status = 'APPROVED' where id = ?", unverifiedId);
        sync(unverifiedId, reload(unverifiedId).getVersion()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("evidence_not_verified"));
        verifyNoInteractions(trello);
    }

    @Test
    void staleVersionIsRejectedWithoutCallingTrello() throws Exception {
        String id = approvedTask();
        sync(id, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("version_conflict"));
        mvc.perform(post("/api/tasks/" + id + "/sync").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(trello);
        assertThat(reload(id).getSyncStatus()).isEqualTo(SyncStatus.NOT_SYNCED);
    }

    // ---------- 8: idempotent ----------
    @Test
    void alreadySyncedTaskReturnsStoredResultWithoutAnotherCard() throws Exception {
        String id = approvedTask();
        cardCreated();
        sync(id, 1).andExpect(status().isOk());
        sync(id, 1).andExpect(status().isOk()).andExpect(jsonPath("$.trello_card_id").value("card123"));
        sync(id, 99).andExpect(status().isOk()).andExpect(jsonPath("$.trello_card_url").value(CARD_URL));
        verify(trello, times(1)).createCard(anyString(), anyString(), anyString(), any());
    }

    // ---------- 9: concurrency ----------
    @Test
    void concurrentSyncRequestsCreateExactlyOneCard() throws Exception {
        String id = approvedTask();
        when(trello.createCard(anyString(), anyString(), anyString(), any())).thenAnswer(inv -> {
            Thread.sleep(400); // keep the winner "in flight" while the others try
            return new TrelloClient.Card("card123", CARD_URL);
        });

        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                barrier.await();
                return sync(id, 1).andReturn().getResponse().getStatus();
            }));
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get());
        }
        pool.shutdown();

        verify(trello, times(1)).createCard(anyString(), anyString(), anyString(), any());
        assertThat(statuses).allMatch(s -> s == 200 || s == 409);
        assertThat(statuses).contains(200);
        assertThat(reload(id).getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
    }

    @Test
    void secondRequestWhileSyncingIsRejected() throws Exception {
        String id = approvedTask();
        jdbc.update("update task_proposals set sync_status = 'SYNCING', sync_attempted_at = ? where id = ?",
                Timestamp.from(Instant.now()), id);
        sync(id, reload(id).getVersion()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("sync_in_progress"));
        verifyNoInteractions(trello);
    }

    // ---------- 10: configuration is covered in TrelloUnconfiguredTest ----------

    // ---------- 11: truthful errors ----------
    @Test
    void invalidTokenIsReportedTruthfullyAndAllowsRetry() throws Exception {
        String id = approvedTask();
        when(trello.createCard(anyString(), anyString(), anyString(), any()))
                .thenThrow(new TrelloException(TrelloException.Kind.AUTH, "Trello rejected the credentials (HTTP 401)."));

        String body = sync(id, 1).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("trello_auth_failed"))
                .andExpect(jsonPath("$.sync_status").value("FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("SECRET");
        TaskProposal failed = reload(id);
        assertThat(failed.getSyncStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(failed.getSyncErrorCode()).isEqualTo("trello_auth_failed");
        assertThat(failed.getTrelloCardId()).isNull();

        // FAILED means definitely no card: a second explicit attempt may create one.
        cardCreated();
        sync(id, failed.getVersion()).andExpect(status().isOk()).andExpect(jsonPath("$.sync_status").value("SYNCED"));
    }

    @Test
    void invalidListIsReportedTruthfully() throws Exception {
        String id = approvedTask();
        when(trello.createCard(anyString(), anyString(), anyString(), any()))
                .thenThrow(new TrelloException(TrelloException.Kind.LIST_INVALID, "HTTP 404"));
        sync(id, 1).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("trello_list_invalid"));
        assertThat(reload(id).getSyncStatus()).isEqualTo(SyncStatus.FAILED);
    }

    @Test
    void rateLimitAndUnreachableAreDefiniteFailures() throws Exception {
        String id = approvedTask();
        when(trello.createCard(anyString(), anyString(), anyString(), any()))
                .thenThrow(new TrelloException(TrelloException.Kind.RATE_LIMITED, "429"));
        sync(id, 1).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("trello_rate_limited"));
        org.mockito.Mockito.doThrow(new TrelloException(TrelloException.Kind.UNREACHABLE, "refused"))
                .when(trello).createCard(anyString(), anyString(), anyString(), any());
        sync(id, reload(id).getVersion()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("trello_unreachable"));
        assertThat(reload(id).getSyncStatus()).isEqualTo(SyncStatus.FAILED);
    }

    // ---------- 12, 14: timeouts and uncertainty ----------
    @Test
    void timeoutDoesNotMarkSyncedAndBlocksBlindRetry() throws Exception {
        String id = approvedTask();
        when(trello.createCard(anyString(), anyString(), anyString(), any()))
                .thenThrow(new TrelloException(TrelloException.Kind.TIMEOUT, "Trello did not answer in time."));

        sync(id, 1).andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("sync_uncertain"))
                .andExpect(jsonPath("$.sync_status").value("UNCERTAIN"));
        TaskProposal t = reload(id);
        assertThat(t.getSyncStatus()).isEqualTo(SyncStatus.UNCERTAIN);
        assertThat(t.getTrelloCardId()).isNull();

        // No automatic or blind retry: a second sync is refused until reconciled.
        sync(id, t.getVersion()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("sync_uncertain"));
        verify(trello, times(1)).createCard(anyString(), anyString(), anyString(), any());
    }

    @Test
    void serverErrorAndMalformedReplyAreUncertainToo() throws Exception {
        String id = approvedTask();
        when(trello.createCard(anyString(), anyString(), anyString(), any()))
                .thenThrow(new TrelloException(TrelloException.Kind.MALFORMED, "unreadable"));
        sync(id, 1).andExpect(status().isBadGateway()).andExpect(jsonPath("$.sync_status").value("UNCERTAIN"));
    }

    // ---------- 13: reconciliation ----------
    @Test
    void reconciliationAdoptsTheExistingCardFoundByItsMarker() throws Exception {
        String id = approvedTask();
        when(trello.createCard(anyString(), anyString(), anyString(), any()))
                .thenThrow(new TrelloException(TrelloException.Kind.TIMEOUT, "timeout"));
        sync(id, 1).andExpect(status().isGatewayTimeout());

        when(trello.findCardByMarker("LIST1", "[meet2action-task-id:" + id + "]"))
                .thenReturn(Optional.of(new TrelloClient.Card("existing9", CARD_URL)));
        reconcile(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.sync_status").value("SYNCED"))
                .andExpect(jsonPath("$.trello_card_id").value("existing9"))
                .andExpect(jsonPath("$.trello_card_url").value(CARD_URL));

        TaskProposal t = reload(id);
        assertThat(t.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
        assertThat(t.getTrelloCardId()).isEqualTo("existing9");
        verify(trello, times(1)).createCard(anyString(), anyString(), anyString(), any()); // no duplicate
    }

    @Test
    void reconciliationThatFindsNothingLetsTheUserRetryExplicitly() throws Exception {
        String id = approvedTask();
        when(trello.createCard(anyString(), anyString(), anyString(), any()))
                .thenThrow(new TrelloException(TrelloException.Kind.TIMEOUT, "timeout"));
        sync(id, 1).andExpect(status().isGatewayTimeout());

        when(trello.findCardByMarker(anyString(), anyString())).thenReturn(Optional.empty());
        reconcile(id).andExpect(status().isOk()).andExpect(jsonPath("$.sync_status").value("FAILED"))
                .andExpect(jsonPath("$.sync_error_code").value("card_not_found"));
        assertThat(reload(id).getTrelloCardId()).isNull();
    }

    @Test
    void reconciliationThatCannotReachTrelloStaysUncertain() throws Exception {
        String id = approvedTask();
        when(trello.createCard(anyString(), anyString(), anyString(), any()))
                .thenThrow(new TrelloException(TrelloException.Kind.TIMEOUT, "timeout"));
        sync(id, 1).andExpect(status().isGatewayTimeout());

        when(trello.findCardByMarker(anyString(), anyString()))
                .thenThrow(new TrelloException(TrelloException.Kind.UNREACHABLE, "down"));
        reconcile(id).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("sync_uncertain"));
        assertThat(reload(id).getSyncStatus()).isEqualTo(SyncStatus.UNCERTAIN);
    }

    @Test
    void reconcileOnlyAppliesToUncertainOrStuckSyncs() throws Exception {
        String id = approvedTask();
        reconcile(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("nothing_to_reconcile"));

        jdbc.update("update task_proposals set sync_status = 'SYNCING', sync_attempted_at = ? where id = ?",
                Timestamp.from(Instant.now()), id);
        reconcile(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("sync_in_progress"));

        // A crashed attempt (SYNCING for longer than the stale window) can be reconciled.
        jdbc.update("update task_proposals set sync_attempted_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(3600)), id);
        when(trello.findCardByMarker(anyString(), anyString()))
                .thenReturn(Optional.of(new TrelloClient.Card("recovered", CARD_URL)));
        reconcile(id).andExpect(status().isOk()).andExpect(jsonPath("$.sync_status").value("SYNCED"));
    }

    // ---------- content is frozen while syncing / after sync ----------
    @Test
    void editAndRejectAreBlockedWhileSyncingUncertainOrSynced() throws Exception {
        for (String state : new String[]{"SYNCING", "UNCERTAIN", "SYNCED"}) {
            clean();
            String id = approvedTask();
            jdbc.update("update task_proposals set sync_status = ?, sync_attempted_at = ? where id = ?",
                    state, Timestamp.from(Instant.now()), id);
            mvc.perform(patch("/api/tasks/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Changed\"}"))
                    .andExpect(status().isConflict());
            mvc.perform(post("/api/tasks/" + id + "/reject")).andExpect(status().isConflict());
            TaskProposal t = reload(id);
            assertThat(t.getTitle()).isEqualTo("Prepare test report");
            assertThat(t.getReviewStatus()).isEqualTo(ReviewStatus.APPROVED);
        }
    }

    @Test
    void editingAfterAFailedSyncResetsReviewAndSyncState() throws Exception {
        String id = approvedTask();
        when(trello.createCard(anyString(), anyString(), anyString(), any()))
                .thenThrow(new TrelloException(TrelloException.Kind.AUTH, "401"));
        sync(id, 1).andExpect(status().isBadGateway());

        mvc.perform(patch("/api/tasks/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"owner\":\"Sam\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review_status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.sync_status").value("NOT_SYNCED"))
                .andExpect(jsonPath("$.sync_error_code").doesNotExist());
    }

    // ---------- never automatic ----------
    @Test
    void analysisApprovalAndListingNeverCallTrello() throws Exception {
        String id = approvedTask();
        mvc.perform(get("/api/tasks")).andExpect(status().isOk());
        mvc.perform(get("/api/tasks/" + id)).andExpect(status().isOk());
        verifyNoInteractions(trello);
        verify(trello, never()).createCard(anyString(), anyString(), anyString(), any());
    }

    @Test
    void statusEndpointReportsConfiguredWithoutLeakingSecrets() throws Exception {
        String body = mvc.perform(get("/api/integrations/trello")).andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true)).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("SECRET", "LIST1");
    }
}
