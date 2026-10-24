package com.meet2action.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.meet2action.backend.agent.AgentClient;
import com.meet2action.backend.agent.AgentExtractResponse;
import com.meet2action.backend.domain.MeetingInsightRepository;
import com.meet2action.backend.domain.MeetingRepository;
import com.meet2action.backend.domain.SyncStatus;
import com.meet2action.backend.domain.TaskProposal;
import com.meet2action.backend.domain.TaskProposalRepository;
import com.meet2action.backend.domain.WorkStatus;
import com.meet2action.backend.trello.TrelloClient;
import com.meet2action.backend.trello.TrelloException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * Three-stage workflow (To Do / In Progress / Completed) through HTTP + service + JPA on in-memory H2.
 * Trello and the agent are MOCKS: no network, no credentials, and nothing can touch a real board.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.trello.api-key=test-key-SECRET", "app.trello.token=test-token-SECRET",
        "app.trello.todo-list-id=TODO1", "app.trello.in-progress-list-id=PROG1", "app.trello.completed-list-id=DONE1"})
class WorkStatusTest {

    private static final String CARD = "card123";
    private static final String URL = "https://trello.com/c/AbCd1234/1-prepare-test-report";

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
    private String[] createTasks() throws Exception {
        int start = TestData.TRANSCRIPT.indexOf(TestData.QUOTE);
        var verified = new AgentExtractResponse.Task("a", "Prepare test report", "Details", "Priya", "Friday", TestData.QUOTE,
                true, "verified", start, start + TestData.QUOTE.length(), "exact", 0.9);
        when(agent.extract(anyString())).thenReturn(new AgentExtractResponse(
                List.of(verified, TestData.unverifiedTask()), List.of(), List.of(), List.of()));
        String json = mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("transcript", TestData.TRANSCRIPT))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new String[]{JsonPath.read(json, "$.tasks[0].id"), JsonPath.read(json, "$.tasks[1].id")};
    }

    private String approvedTask() throws Exception {
        String id = createTasks()[0];
        mvc.perform(post("/api/tasks/" + id + "/approve").contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk());
        return id;
    }

    private void cardCreated() {
        doReturn(new TrelloClient.Card(CARD, URL)).when(trello).createCard(anyString(), anyString(), anyString(), any());
    }

    /** Approved and synced (card created in the To Do list). */
    private String syncedTask() throws Exception {
        String id = approvedTask();
        cardCreated();
        mvc.perform(post("/api/tasks/" + id + "/sync").contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sync_status").value("SYNCED"));
        return id;
    }

    private TaskProposal reload(String id) {
        return tasks.findById(id).orElseThrow();
    }

    private ResultActions move(String id, String workStatus, long version) throws Exception {
        return mvc.perform(post("/api/tasks/" + id + "/work-status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"work_status\":\"" + workStatus + "\",\"version\":" + version + "}"));
    }

    private ResultActions move(String id, String workStatus) throws Exception {
        return move(id, workStatus, reload(id).getVersion());
    }

    // ---------- creation in To Do; nothing becomes Completed implicitly ----------
    @Test
    void cardIsCreatedInTheToDoListAndWorkStatusStartsAsToDo() throws Exception {
        String id = syncedTask();

        verify(trello, times(1)).createCard(eq("TODO1"), eq("Prepare test report"), anyString(), any());
        TaskProposal t = reload(id);
        assertThat(t.getTrelloListId()).isEqualTo("TODO1");
        assertThat(t.getWorkStatus()).isEqualTo(WorkStatus.TO_DO);
        assertThat(t.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
        mvc.perform(get("/api/tasks/" + id)).andExpect(jsonPath("$.work_status").value("TO_DO"));
    }

    @Test
    void approvalAndSyncNeverCompleteATask() throws Exception {
        String id = syncedTask();
        assertThat(reload(id).getWorkStatus()).isEqualTo(WorkStatus.TO_DO);
        verify(trello, never()).moveCard(anyString(), anyString());
    }

    // ---------- moves reuse the existing card ----------
    @Test
    void movesTheExistingCardFromToDoToInProgressToCompleted() throws Exception {
        String id = syncedTask();

        move(id, "IN_PROGRESS").andExpect(status().isOk())
                .andExpect(jsonPath("$.work_status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.sync_status").value("SYNCED"))
                .andExpect(jsonPath("$.trello_card_id").value(CARD));
        verify(trello).moveCard(CARD, "PROG1");
        assertThat(reload(id).getWorkStatus()).isEqualTo(WorkStatus.IN_PROGRESS);

        move(id, "COMPLETED").andExpect(status().isOk())
                .andExpect(jsonPath("$.work_status").value("COMPLETED"))
                .andExpect(jsonPath("$.trello_card_id").value(CARD))
                .andExpect(jsonPath("$.trello_card_url").value(URL));
        verify(trello).moveCard(CARD, "DONE1");

        TaskProposal t = reload(id);
        assertThat(t.getWorkStatus()).isEqualTo(WorkStatus.COMPLETED);
        assertThat(t.getWorkStatusUpdatedAt()).isNotNull();
        assertThat(t.getTrelloCardId()).isEqualTo(CARD);
        assertThat(t.getTrelloCardUrl()).isEqualTo(URL);
        assertThat(t.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
        assertThat(t.getWorkMoveTarget()).as("move claim released").isNull();
        // No second card was ever created.
        verify(trello, times(1)).createCard(anyString(), anyString(), anyString(), any());
    }

    @Test
    void reopeningMovesTheSameCardBack() throws Exception {
        String id = syncedTask();
        move(id, "COMPLETED").andExpect(status().isOk());
        move(id, "IN_PROGRESS").andExpect(status().isOk()).andExpect(jsonPath("$.work_status").value("IN_PROGRESS"));
        move(id, "TO_DO").andExpect(status().isOk()).andExpect(jsonPath("$.work_status").value("TO_DO"));
        verify(trello).moveCard(CARD, "TODO1");
        verify(trello, times(1)).createCard(anyString(), anyString(), anyString(), any());
    }

    @Test
    void repeatedSyncAfterMovesReusesTheExistingCard() throws Exception {
        String id = syncedTask();
        move(id, "IN_PROGRESS").andExpect(status().isOk());
        mvc.perform(post("/api/tasks/" + id + "/sync").contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.trello_card_id").value(CARD))
                .andExpect(jsonPath("$.work_status").value("IN_PROGRESS"));
        verify(trello, times(1)).createCard(anyString(), anyString(), anyString(), any());
    }

    @Test
    void sameStatusIsANoOpWithoutTrelloCall() throws Exception {
        String id = syncedTask();
        move(id, "TO_DO").andExpect(status().isOk()).andExpect(jsonPath("$.work_status").value("TO_DO"));
        verify(trello, never()).moveCard(anyString(), anyString());
    }

    // ---------- gates ----------
    @Test
    void aTaskWithoutACardMustBeSyncedFirst() throws Exception {
        String id = approvedTask(); // approved but NOT synced
        move(id, "IN_PROGRESS").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("not_synced"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Sync it to Trello first")));
        verify(trello, never()).moveCard(anyString(), anyString());
        verify(trello, never()).createCard(anyString(), anyString(), anyString(), any());
        assertThat(reload(id).getWorkStatus()).isEqualTo(WorkStatus.TO_DO);
    }

    @Test
    void anUnapprovedTaskCannotChangeWorkStatus() throws Exception {
        String id = createTasks()[0]; // pending review
        move(id, "IN_PROGRESS", 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("task_not_approved"));
        verifyNoMoreInteractions(trello);
    }

    @Test
    void staleVersionIsRejected() throws Exception {
        String id = syncedTask();
        move(id, "IN_PROGRESS", reload(id).getVersion() - 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("version_conflict"));
        verify(trello, never()).moveCard(anyString(), anyString());
        assertThat(reload(id).getWorkStatus()).isEqualTo(WorkStatus.TO_DO);
    }

    @Test
    void invalidRequestsAreRejectedByTheServer() throws Exception {
        String id = syncedTask();
        mvc.perform(post("/api/tasks/" + id + "/work-status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"work_status\":\"DONE_AND_DUSTED\",\"version\":3}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/tasks/" + id + "/work-status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"work_status\":\"COMPLETED\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/tasks/" + id + "/work-status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"work_status\":\"COMPLETED\",\"version\":3,\"review_status\":\"APPROVED\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown_field"));
        mvc.perform(post("/api/tasks/does-not-exist/work-status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"work_status\":\"COMPLETED\",\"version\":3}")).andExpect(status().isNotFound());
        verify(trello, never()).moveCard(anyString(), anyString());
    }

    // ---------- failures: never marked as done, claim released, retry possible ----------
    @Test
    void failedMovesLeaveTheWorkStatusUnchangedAndAreTruthful() throws Exception {
        String id = syncedTask();
        for (Object[] c : new Object[][]{
                {TrelloException.Kind.AUTH, 502, "trello_auth_failed"},
                {TrelloException.Kind.LIST_INVALID, 502, "trello_card_or_list_not_found"},
                {TrelloException.Kind.RATE_LIMITED, 429, "trello_rate_limited"},
                {TrelloException.Kind.UNREACHABLE, 503, "trello_unreachable"},
                {TrelloException.Kind.REJECTED, 502, "trello_rejected"}}) {
            doThrow(new TrelloException((TrelloException.Kind) c[0], "boom")).when(trello).moveCard(anyString(), anyString());
            String body = move(id, "IN_PROGRESS").andExpect(status().is((Integer) c[1]))
                    .andExpect(jsonPath("$.code").value((String) c[2])).andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("SECRET");
            TaskProposal t = reload(id);
            assertThat(t.getWorkStatus()).isEqualTo(WorkStatus.TO_DO);
            assertThat(t.getWorkMoveTarget()).as("claim released after failure").isNull();
            assertThat(t.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
        }
    }

    @Test
    void timeoutIsReportedAsUncertainAndDoesNotChangeStatusButCanBeRetried() throws Exception {
        String id = syncedTask();
        doThrow(new TrelloException(TrelloException.Kind.TIMEOUT, "slow")).when(trello).moveCard(anyString(), anyString());

        move(id, "IN_PROGRESS").andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("work_move_uncertain"));
        assertThat(reload(id).getWorkStatus()).isEqualTo(WorkStatus.TO_DO);
        assertThat(reload(id).getWorkMoveTarget()).isNull();

        org.mockito.Mockito.doNothing().when(trello).moveCard(anyString(), anyString());
        move(id, "IN_PROGRESS").andExpect(status().isOk()).andExpect(jsonPath("$.work_status").value("IN_PROGRESS"));
        verify(trello, times(1)).createCard(anyString(), anyString(), anyString(), any());
    }

    // ---------- concurrency ----------
    @Test
    void concurrentRequestsMoveTheCardExactlyOnce() throws Exception {
        String id = syncedTask();
        long version = reload(id).getVersion();
        org.mockito.Mockito.doAnswer(inv -> {
            Thread.sleep(400);
            return null;
        }).when(trello).moveCard(anyString(), anyString());

        int threads = 6;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                return move(id, "IN_PROGRESS", version).andReturn().getResponse().getStatus();
            }));
        }
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : futures) {
            codes.add(f.get());
        }
        pool.shutdown();

        verify(trello, times(1)).moveCard(CARD, "PROG1");
        assertThat(codes).allMatch(c -> c == 200 || c == 409);
        assertThat(codes).contains(200);
        assertThat(reload(id).getWorkStatus()).isEqualTo(WorkStatus.IN_PROGRESS);
    }

    @Test
    void aLiveMoveClaimBlocksOtherChangesButAStaleOneIsReclaimable() throws Exception {
        String id = syncedTask();
        jdbc.update("update task_proposals set work_move_target = 'COMPLETED', work_move_started_at = ? where id = ?",
                Timestamp.from(Instant.now()), id);
        move(id, "IN_PROGRESS").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("work_move_in_progress"));
        verify(trello, never()).moveCard(anyString(), anyString());

        jdbc.update("update task_proposals set work_move_started_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(3600)), id);
        move(id, "IN_PROGRESS").andExpect(status().isOk());
        verify(trello).moveCard(CARD, "PROG1");
    }

    // ---------- the request sent to Trello ----------
    @Test
    void moveUsesTheSavedCardIdAndTheListForTheTargetStatus() throws Exception {
        String id = syncedTask();
        move(id, "COMPLETED").andExpect(status().isOk());
        ArgumentCaptor<String> card = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> list = ArgumentCaptor.forClass(String.class);
        verify(trello).moveCard(card.capture(), list.capture());
        assertThat(card.getValue()).isEqualTo(reload(id).getTrelloCardId());
        assertThat(list.getValue()).isEqualTo("DONE1");
    }

    @Test
    void statusEndpointReportsWorkListsConfigured() throws Exception {
        mvc.perform(get("/api/integrations/trello")).andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.work_lists_configured").value(true));
    }
}
