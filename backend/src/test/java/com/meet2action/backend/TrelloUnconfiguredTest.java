package com.meet2action.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.meet2action.backend.agent.AgentClient;
import com.meet2action.backend.domain.MeetingInsightRepository;
import com.meet2action.backend.domain.MeetingRepository;
import com.meet2action.backend.domain.SyncStatus;
import com.meet2action.backend.domain.TaskProposalRepository;
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

/** The app must start and work with no Trello credentials; only syncing is refused. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.trello.api-key=", "app.trello.token=", "app.trello.list-id="})
class TrelloUnconfiguredTest {

    @Autowired MockMvc mvc;
    @Autowired MeetingRepository meetings;
    @Autowired TaskProposalRepository tasks;
    @Autowired MeetingInsightRepository insights;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @MockitoBean AgentClient agent;
    @MockitoBean TrelloClient trello;

    @Test
    void missingCredentialsGiveAClearConfigurationErrorAndChangeNothing() throws Exception {
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

        mvc.perform(post("/api/tasks/" + id + "/sync").contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("trello_not_configured"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("TRELLO_API_KEY")));

        // Refused before claiming: the task is untouched and Trello was never contacted.
        assertThat(tasks.findById(id).orElseThrow().getSyncStatus()).isEqualTo(SyncStatus.NOT_SYNCED);
        assertThat(tasks.findById(id).orElseThrow().getVersion()).isEqualTo(1L);
        verifyNoInteractions(trello);

        mvc.perform(get("/api/integrations/trello")).andExpect(jsonPath("$.configured").value(false));
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }
}
