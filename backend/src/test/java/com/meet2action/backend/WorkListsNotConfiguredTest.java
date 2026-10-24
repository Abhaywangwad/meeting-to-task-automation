package com.meet2action.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.meet2action.backend.domain.WorkStatus;
import com.meet2action.backend.trello.TrelloClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Backwards compatibility: only the legacy TRELLO_LIST_ID is set. Cards can still be created (in that list, treated
 * as To Do) but moving cards needs the other two lists and says so clearly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.trello.api-key=k", "app.trello.token=t", "app.trello.list-id=LEGACY1",
        "app.trello.todo-list-id=", "app.trello.in-progress-list-id=", "app.trello.completed-list-id="})
class WorkListsNotConfiguredTest {

    @Autowired MockMvc mvc;
    @Autowired MeetingRepository meetings;
    @Autowired TaskProposalRepository tasks;
    @Autowired MeetingInsightRepository insights;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @MockitoBean AgentClient agent;
    @MockitoBean TrelloClient trello;

    @Test
    void legacyListIdStillCreatesCardsButMovingNeedsTheOtherLists() throws Exception {
        insights.deleteAll();
        tasks.deleteAll();
        meetings.deleteAll();
        when(agent.extract(anyString())).thenReturn(TestData.response());
        String json = mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("transcript", TestData.TRANSCRIPT))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.<List<String>>read(json, "$.tasks[?(@.evidence_verified==true)].id").get(0);
        mvc.perform(post("/api/tasks/" + id + "/approve").contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk());
        doReturn(new TrelloClient.Card("c1", "https://trello.com/c/x")).when(trello)
                .createCard(anyString(), anyString(), anyString(), any());

        mvc.perform(get("/api/integrations/trello")).andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.work_lists_configured").value(false));

        mvc.perform(post("/api/tasks/" + id + "/sync").contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk());
        verify(trello).createCard(org.mockito.ArgumentMatchers.eq("LEGACY1"), anyString(), anyString(), any());

        mvc.perform(post("/api/tasks/" + id + "/work-status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"work_status\":\"IN_PROGRESS\",\"version\":" + tasks.findById(id).orElseThrow().getVersion() + "}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("trello_list_not_configured"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("TRELLO_IN_PROGRESS_LIST_ID")));
        verify(trello, never()).moveCard(anyString(), anyString());
        assertThat(tasks.findById(id).orElseThrow().getWorkStatus()).isEqualTo(WorkStatus.TO_DO);
    }
}
