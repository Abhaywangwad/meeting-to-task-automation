package com.meet2action.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.meet2action.backend.agent.AgentClient;
import com.meet2action.backend.domain.MeetingInsightRepository;
import com.meet2action.backend.domain.MeetingRepository;
import com.meet2action.backend.domain.TaskProposalRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Real MySQL integration test. Uses the default profile, so the datasource comes from the MYSQL_*
 * environment variables. Skipped entirely unless MYSQL_PASSWORD is set. The agent is still mocked.
 * It only creates (and afterwards deletes) its own uniquely-named meeting; nothing else is touched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+")
@org.springframework.test.context.TestPropertySource(properties = {
        "app.trello.api-key=fake-key", "app.trello.token=fake-token", "app.trello.list-id=FAKELIST"})
class MySqlIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MeetingRepository meetings;
    @Autowired TaskProposalRepository tasks;
    @Autowired MeetingInsightRepository insights;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @MockitoBean AgentClient agent;
    @MockitoBean com.meet2action.backend.trello.TrelloClient trello;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

    private String meetingId;

    @AfterEach
    void cleanUpOnlyOwnData() {
        if (meetingId != null) {
            insights.deleteAll(insights.findByMeetingIdOrderByTypeAscPositionAsc(meetingId));
            tasks.deleteAll(tasks.findByMeetingIdOrderByPositionAsc(meetingId));
            meetings.deleteById(meetingId);
        }
    }

    /** Regression: long text columns must not be truncated (they once mapped to TINYTEXT). */
    @Test
    void largeTextRoundTripsWithoutTruncation() throws Exception {
        String quote = "Priya, please prepare the report by Friday — é中文.";
        String transcript = "Alex: " + quote + "\n" + "filler line for size\n".repeat(7000)
                + "# it-run " + java.util.UUID.randomUUID();
        int start = transcript.indexOf(quote);
        String longDescription = "d".repeat(5000);
        var task = new com.meet2action.backend.agent.AgentExtractResponse.Task("a", "Big", longDescription, null, null,
                quote, true, "verified", start, start + quote.length(), "exact", null);
        when(agent.extract(anyString())).thenReturn(new com.meet2action.backend.agent.AgentExtractResponse(
                java.util.List.of(task), java.util.List.of(), java.util.List.of(), java.util.List.of()));

        String json = mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("transcript", transcript))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        meetingId = JsonPath.read(json, "$.id");

        assertThat(transcript.length()).isGreaterThan(100_000);
        assertThat(meetings.findById(meetingId).get().getTranscript()).isEqualTo(transcript);
        var saved = tasks.findByMeetingIdOrderByPositionAsc(meetingId).get(0);
        assertThat(saved.getDescription()).isEqualTo(longDescription);
        assertThat(saved.getEvidenceQuote()).isEqualTo(quote);
    }

    /** Real MySQL: the atomic sync claim and persisted card data. Trello itself is a mock. */
    @Test
    void syncClaimIsAtomicAndCardDataPersistsInMySql() throws Exception {
        String unique = TestData.TRANSCRIPT + "\n# it-sync " + java.util.UUID.randomUUID();
        when(agent.extract(anyString())).thenReturn(TestData.response());
        String json = mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("transcript", unique))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        meetingId = JsonPath.read(json, "$.id");
        String taskId = JsonPath.<java.util.List<String>>read(json, "$.tasks[?(@.evidence_verified==true)].id").get(0);
        mvc.perform(post("/api/tasks/" + taskId + "/approve").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}")).andExpect(status().isOk());

        var tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        java.time.Instant now = java.time.Instant.now();
        Integer first = tx.execute(s -> tasks.claimForSync(taskId, 1, now));
        Integer second = tx.execute(s -> tasks.claimForSync(taskId, 1, now));
        assertThat(first).isEqualTo(1);
        assertThat(second).as("second claim loses").isEqualTo(0);
        assertThat(tasks.findById(taskId).get().getSyncStatus())
                .isEqualTo(com.meet2action.backend.domain.SyncStatus.SYNCING);

        // A stuck SYNCING attempt is not reconcilable until it is stale; after that it can be adopted.
        when(trello.findCardByMarker(anyString(), anyString())).thenReturn(java.util.Optional.of(
                new com.meet2action.backend.trello.TrelloClient.Card("it-card", "https://trello.com/c/it")));
        mvc.perform(post("/api/tasks/" + taskId + "/sync/reconcile")).andExpect(status().isConflict());
        // With a staleness cutoff in the future the attempt counts as abandoned, so the claim succeeds once.
        Integer stale = tx.execute(s -> tasks.claimForReconcile(taskId, now, now.plusSeconds(10)));
        assertThat(stale).isEqualTo(1);
    }

    @Test
    void endToEndSyncAgainstRealMySqlWithMockedTrello() throws Exception {
        String unique = TestData.TRANSCRIPT + "\n# it-sync2 " + java.util.UUID.randomUUID();
        when(agent.extract(anyString())).thenReturn(TestData.response());
        String json = mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("transcript", unique))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        meetingId = JsonPath.read(json, "$.id");
        String taskId = JsonPath.<java.util.List<String>>read(json, "$.tasks[?(@.evidence_verified==true)].id").get(0);
        mvc.perform(post("/api/tasks/" + taskId + "/approve").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}")).andExpect(status().isOk());
        when(trello.createCard(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.meet2action.backend.trello.TrelloClient.Card("it-card", "https://trello.com/c/it"));

        mvc.perform(post("/api/tasks/" + taskId + "/sync").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sync_status").value("SYNCED"));

        var t = tasks.findById(taskId).get();   // fresh read from MySQL
        assertThat(t.getTrelloCardId()).isEqualTo("it-card");
        assertThat(t.getTrelloCardUrl()).isEqualTo("https://trello.com/c/it");
        assertThat(t.getSyncedAt()).isNotNull();
        assertThat(t.getTrelloListId()).isEqualTo("FAKELIST");
    }

    @Test
    void fullWorkflowAgainstRealMySql() throws Exception {
        mvc.perform(get("/api/health/db")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));

        String unique = TestData.TRANSCRIPT + "\n# it-run " + java.util.UUID.randomUUID();
        when(agent.extract(anyString())).thenReturn(TestData.response());

        String json = mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("transcript", unique))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        meetingId = JsonPath.read(json, "$.id");
        String taskId = JsonPath.<java.util.List<String>>read(json, "$.tasks[?(@.evidence_verified==true)].id").get(0);

        // Re-read from MySQL (fresh query, not the response) to prove it was persisted.
        assertThat(meetings.findById(meetingId)).isPresent();
        assertThat(meetings.findById(meetingId).get().getTranscript()).isEqualTo(unique);
        assertThat(tasks.findByMeetingIdOrderByPositionAsc(meetingId)).hasSize(2);
        assertThat(insights.findByMeetingIdOrderByTypeAscPositionAsc(meetingId)).hasSize(3);

        mvc.perform(post("/api/tasks/" + taskId + "/approve").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}")).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/tasks/" + taskId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"owner\":\"Sam\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.review_status").value("PENDING_REVIEW"));
        assertThat(tasks.findById(taskId).get().getVersion()).isEqualTo(2);
    }
}
