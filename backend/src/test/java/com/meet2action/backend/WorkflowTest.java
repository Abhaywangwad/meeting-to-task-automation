package com.meet2action.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.meet2action.backend.agent.AgentClient;
import com.meet2action.backend.agent.AgentException;
import com.meet2action.backend.domain.EvidenceStatus;
import com.meet2action.backend.domain.ExtractionStatus;
import com.meet2action.backend.domain.Meeting;
import com.meet2action.backend.domain.MeetingInsightRepository;
import com.meet2action.backend.domain.MeetingRepository;
import com.meet2action.backend.domain.ReviewStatus;
import com.meet2action.backend.domain.SyncStatus;
import com.meet2action.backend.domain.TaskProposal;
import com.meet2action.backend.domain.TaskProposalRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * End-to-end through HTTP, service layer and JPA. The agent client is mocked (no FastAPI/LLM) and the
 * database is in-memory H2, so these tests do NOT verify MySQL behaviour; see MySqlIntegrationTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkflowTest {

    @Autowired MockMvc mvc;
    @Autowired MeetingRepository meetings;
    @Autowired TaskProposalRepository tasks;
    @Autowired MeetingInsightRepository insights;
    @Autowired ApplicationContext context;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @MockitoBean AgentClient agent;
    @MockitoBean com.meet2action.backend.trello.TrelloClient trello;

    @BeforeEach
    void clean() {
        insights.deleteAll();
        tasks.deleteAll();
        meetings.deleteAll();
    }

    // ---------- helpers ----------
    private String json(String transcript) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of("transcript", transcript));
    }

    private ResultActions submit(String transcript) throws Exception {
        return mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                .content(json(transcript)));
    }

    /** Creates a meeting from the standard mocked extraction; returns the meeting JSON. */
    private String createMeeting() throws Exception {
        when(agent.extract(anyString())).thenReturn(TestData.response());
        return submit(TestData.TRANSCRIPT).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private String verifiedTaskId(String meetingJson) {
        return JsonPath.<List<String>>read(meetingJson, "$.tasks[?(@.evidence_verified==true)].id").get(0);
    }

    private String unverifiedTaskId(String meetingJson) {
        return JsonPath.<List<String>>read(meetingJson, "$.tasks[?(@.evidence_verified==false)].id").get(0);
    }

    private ResultActions approve(String id, Object version) throws Exception {
        return mvc.perform(post("/api/tasks/" + id + "/approve").contentType(MediaType.APPLICATION_JSON)
                .content(version == null ? "{}" : "{\"version\":" + version + "}"));
    }

    private ResultActions reject(String id) throws Exception {
        return mvc.perform(post("/api/tasks/" + id + "/reject"));
    }

    private ResultActions patchTask(String id, String json) throws Exception {
        return mvc.perform(patch("/api/tasks/" + id).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private TaskProposal reload(String id) {
        return tasks.findById(id).orElseThrow();
    }

    // ---------- 1 & 2: submit, mocked agent output, persistence ----------
    @Test
    void submittingTranscriptPersistsMeetingTasksAndInsights() throws Exception {
        String json = createMeeting();

        assertThat(JsonPath.<String>read(json, "$.extraction_status")).isEqualTo("COMPLETED");
        assertThat(JsonPath.<List<Object>>read(json, "$.tasks")).hasSize(2);
        assertThat(JsonPath.<List<Object>>read(json, "$.decisions")).hasSize(1);
        assertThat(JsonPath.<List<Object>>read(json, "$.unresolved_questions")).hasSize(1);
        assertThat(JsonPath.<List<Object>>read(json, "$.completed_updates")).hasSize(1);

        assertThat(meetings.count()).isEqualTo(1);
        assertThat(tasks.count()).isEqualTo(2);
        assertThat(insights.count()).isEqualTo(3);
        Meeting m = meetings.findAll().get(0);
        assertThat(m.getTranscript()).isEqualTo(TestData.TRANSCRIPT);

        TaskProposal verified = reload(verifiedTaskId(json));
        assertThat(verified.getEvidenceStatus()).isEqualTo(EvidenceStatus.VERIFIED);
        assertThat(verified.getEvidenceStart()).isEqualTo(TestData.TRANSCRIPT.indexOf(TestData.QUOTE));
        assertThat(verified.getReviewStatus()).isEqualTo(ReviewStatus.PENDING_REVIEW);
        assertThat(verified.getSyncStatus()).isEqualTo(SyncStatus.NOT_SYNCED);
        assertThat(verified.getVersion()).isZero();

        // Missing owner/deadline stay null: the backend never fills them in.
        TaskProposal unverified = reload(unverifiedTaskId(json));
        assertThat(unverified.getOwner()).isNull();
        assertThat(unverified.getDeadline()).isNull();
        assertThat(unverified.getEvidenceStatus()).isEqualTo(EvidenceStatus.UNVERIFIED);
    }

    @Test
    void validEmptyExtractionIsCompletedNotFailed() throws Exception {
        when(agent.extract(anyString())).thenReturn(TestData.emptyResponse());
        submit("Just chatting, nothing decided.").andExpect(status().isCreated())
                .andExpect(jsonPath("$.extraction_status").value("COMPLETED"))
                .andExpect(jsonPath("$.tasks").isEmpty());
    }

    @Test
    void blankOrOversizedTranscriptIsRejectedWithoutCallingAgent() throws Exception {
        submit("   ").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_error"));
        submit("x".repeat(200_001)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verify(agent, never()).extract(anyString());
        assertThat(meetings.count()).isZero();
    }

    // ---------- 3: agent failure ----------
    @Test
    void agentFailureIsRecordedAndNoTasksAreFabricated() throws Exception {
        when(agent.extract(anyString())).thenThrow(
                new AgentException(HttpStatus.SERVICE_UNAVAILABLE, "agent_unavailable", "The agent service is unreachable."));

        String body = submit(TestData.TRANSCRIPT).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("agent_unavailable"))
                .andExpect(jsonPath("$.extraction_status").value("FAILED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(tasks.count()).isZero();
        assertThat(insights.count()).isZero();
        Meeting m = meetings.findAll().get(0);
        assertThat(m.getExtractionStatus()).isEqualTo(ExtractionStatus.FAILED);
        assertThat(m.getErrorCode()).isEqualTo("agent_unavailable");

        String meetingId = JsonPath.read(body, "$.meeting_id");
        mvc.perform(get("/api/meetings/" + meetingId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.extraction_status").value("FAILED"))
                .andExpect(jsonPath("$.error_code").value("agent_unavailable"))
                .andExpect(jsonPath("$.tasks").isEmpty());
    }

    @Test
    void unexpectedAgentClientErrorIsAlsoRecordedAsFailed() throws Exception {
        when(agent.extract(anyString())).thenThrow(new IllegalStateException("kaboom with internals"));
        submit(TestData.TRANSCRIPT).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Unexpected error during extraction."));
        assertThat(meetings.findAll().get(0).getExtractionStatus()).isEqualTo(ExtractionStatus.FAILED);
        assertThat(tasks.count()).isZero();
    }

    // ---------- 4: retrieval ----------
    @Test
    void meetingAndTasksCanBeRetrievedAndFiltered() throws Exception {
        String json = createMeeting();
        String meetingId = JsonPath.read(json, "$.id");
        String approvable = verifiedTaskId(json);
        approve(approvable, 0).andExpect(status().isOk());

        mvc.perform(get("/api/meetings/" + meetingId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.transcript").value(TestData.TRANSCRIPT))
                .andExpect(jsonPath("$.tasks.length()").value(2))
                .andExpect(jsonPath("$.decisions[0].evidence_verified").value(true));
        mvc.perform(get("/api/meetings/does-not-exist")).andExpect(status().isNotFound());

        mvc.perform(get("/api/tasks")).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/tasks").param("meeting_id", meetingId)).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/tasks").param("meeting_id", "other")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/tasks").param("review_status", "APPROVED"))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(approvable));
        mvc.perform(get("/api/tasks").param("review_status", "PENDING_REVIEW"))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/tasks").param("review_status", "BOGUS")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/tasks/" + approvable)).andExpect(jsonPath("$.review_status").value("APPROVED"));
    }

    // ---------- 5: approve ----------
    @Test
    void taskWithVerifiedEvidenceCanBeApproved() throws Exception {
        String id = verifiedTaskId(createMeeting());
        approve(id, 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.review_status").value("APPROVED"))
                .andExpect(jsonPath("$.sync_status").value("NOT_SYNCED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.approved_at").isNotEmpty());

        TaskProposal t = reload(id);
        assertThat(t.getReviewStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(t.getApprovedAt()).isNotNull();
        assertThat(t.getEvidenceStatus()).isEqualTo(EvidenceStatus.VERIFIED);
    }

    @Test
    void approvalRequiresTheCurrentVersion() throws Exception {
        String id = verifiedTaskId(createMeeting());
        approve(id, null).andExpect(status().isBadRequest());
        approve(id, 5).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("version_conflict"));
        assertThat(reload(id).getReviewStatus()).isEqualTo(ReviewStatus.PENDING_REVIEW);
    }

    // ---------- 7: unverified evidence ----------
    @Test
    void approvalIsRefusedForUnverifiedEvidenceAndStatusDoesNotChange() throws Exception {
        String id = unverifiedTaskId(createMeeting());
        approve(id, 0).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("evidence_not_verified"));

        TaskProposal t = reload(id);
        assertThat(t.getReviewStatus()).isEqualTo(ReviewStatus.PENDING_REVIEW);
        assertThat(t.getEvidenceStatus()).isEqualTo(EvidenceStatus.UNVERIFIED);
    }

    @Test
    void editingCannotMakeUnverifiedEvidenceApprovable() throws Exception {
        String id = unverifiedTaskId(createMeeting());
        patchTask(id, "{\"title\":\"Edited title\",\"description\":\"Edited\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence_verified").value(false));
        approve(id, reload(id).getVersion()).andExpect(status().isUnprocessableEntity());
    }

    // ---------- 6: reject ----------
    @Test
    void pendingTaskCanBeRejectedAndIsRetained() throws Exception {
        String json = createMeeting();
        String id = verifiedTaskId(json);
        reject(id).andExpect(status().isOk()).andExpect(jsonPath("$.review_status").value("REJECTED"))
                .andExpect(jsonPath("$.rejected_at").isNotEmpty())
                .andExpect(jsonPath("$.sync_status").value("NOT_SYNCED"));

        assertThat(tasks.count()).isEqualTo(2);
        assertThat(meetings.count()).isEqualTo(1);
        TaskProposal t = reload(id);
        assertThat(t.getEvidenceQuote()).isEqualTo(TestData.QUOTE);
        assertThat(t.getRejectedAt()).isNotNull();
    }

    @Test
    void rejectedTaskNeverBecomesApprovedOrEditable() throws Exception {
        String id = verifiedTaskId(createMeeting());
        reject(id).andExpect(status().isOk());
        long version = reload(id).getVersion();

        approve(id, version).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("invalid_state"));
        patchTask(id, "{\"title\":\"x\"}").andExpect(status().isConflict());
        reject(id).andExpect(status().isConflict());
        assertThat(reload(id).getReviewStatus()).isEqualTo(ReviewStatus.REJECTED);
    }

    @Test
    void approvedTaskCannotBeApprovedTwiceButCanBeRejected() throws Exception {
        String id = verifiedTaskId(createMeeting());
        approve(id, 0).andExpect(status().isOk());
        approve(id, 1).andExpect(status().isConflict());
        reject(id).andExpect(status().isOk()).andExpect(jsonPath("$.approved_at").doesNotExist());
    }

    // ---------- 8: edit resets approval ----------
    @Test
    void editingAnApprovedTaskInvalidatesTheApproval() throws Exception {
        String id = verifiedTaskId(createMeeting());
        approve(id, 0).andExpect(status().isOk());

        patchTask(id, "{\"owner\":\"Sam\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.review_status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.approved_at").doesNotExist())
                .andExpect(jsonPath("$.owner").value("Sam"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.evidence_status").value("VERIFIED"));

        TaskProposal t = reload(id);
        assertThat(t.getReviewStatus()).isEqualTo(ReviewStatus.PENDING_REVIEW);
        assertThat(t.getApprovedAt()).isNull();
        // The old approval (version 0/1) cannot be replayed.
        approve(id, 1).andExpect(status().isConflict());
        approve(id, 2).andExpect(status().isOk());
    }

    @Test
    void noOpEditKeepsApprovalAndVersion() throws Exception {
        String id = verifiedTaskId(createMeeting());
        approve(id, 0).andExpect(status().isOk());
        patchTask(id, "{\"owner\":\"Priya\",\"deadline\":\"Friday\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.review_status").value("APPROVED"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void editCanClearOwnerWithEmptyStringAndRespectsVersion() throws Exception {
        String id = verifiedTaskId(createMeeting());
        patchTask(id, "{\"owner\":\"\",\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").doesNotExist());
        patchTask(id, "{\"title\":\"Stale\",\"version\":0}").andExpect(status().isConflict());
        patchTask(id, "{\"title\":\"   \"}").andExpect(status().isBadRequest());
    }

    // ---------- 9: client cannot set evidence ----------
    @Test
    void clientCannotSetEvidenceVerification() throws Exception {
        String id = unverifiedTaskId(createMeeting());
        for (String body : new String[]{
                "{\"evidence_verified\":true}",
                "{\"evidence_status\":\"VERIFIED\"}",
                "{\"title\":\"ok\",\"evidence_verified\":true}",
                "{\"evidence_quote\":\"Someone should update the docs.\"}",
                "{\"review_status\":\"APPROVED\"}",
                "{\"sync_status\":\"SYNCED\"}"}) {
            patchTask(id, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown_field"));
        }
        TaskProposal t = reload(id);
        assertThat(t.getEvidenceStatus()).isEqualTo(EvidenceStatus.UNVERIFIED);
        assertThat(t.getEvidenceQuote()).isEqualTo("Someone must rewrite all docs");
        assertThat(t.getReviewStatus()).isEqualTo(ReviewStatus.PENDING_REVIEW);
        assertThat(t.getSyncStatus()).isEqualTo(SyncStatus.NOT_SYNCED);
        assertThat(t.getTitle()).isEqualTo("Update docs");
        // Approve endpoint likewise ignores/rejects any attempt to smuggle in evidence flags.
        mvc.perform(post("/api/tasks/" + id + "/approve").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"evidence_verified\":true}")).andExpect(status().isBadRequest());
    }

    // ---------- 10: analysis, approval and rejection never touch Trello ----------
    @Test
    void analysisApprovalAndRejectionNeverCallTrello() throws Exception {
        String json = createMeeting();
        String id = verifiedTaskId(json);
        approve(id, 0).andExpect(status().isOk());
        reject(unverifiedTaskId(json)).andExpect(status().isOk());

        // Sync is an explicit separate endpoint; none of the actions above may reach the Trello client.
        org.mockito.Mockito.verifyNoInteractions(trello);
        tasks.findAll().forEach(t -> assertThat(t.getSyncStatus()).isEqualTo(SyncStatus.NOT_SYNCED));
        // The only collaborator the backend ever called is the agent's extract endpoint, exactly once.
        verify(agent).extract(anyString());
        verifyNoMoreInteractions(agent);
    }
}
