package com.meet2action.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meet2action.backend.agent.AgentClient;
import com.meet2action.backend.domain.MeetingRepository;
import com.meet2action.backend.domain.TaskProposalRepository;
import com.meet2action.backend.service.MeetingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.meetings.dedupe-window-seconds=30")
class DuplicateSubmissionTest {

    @Autowired MeetingService service;
    @Autowired MeetingRepository meetings;
    @Autowired TaskProposalRepository tasks;
    @MockitoBean AgentClient agent;

    @Test
    void retryOfSameTranscriptReturnsExistingMeetingWithoutSecondExtraction() {
        when(agent.extract(anyString())).thenReturn(TestData.response());

        var first = service.create(TestData.TRANSCRIPT);
        var second = service.create(TestData.TRANSCRIPT);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.meeting().id()).isEqualTo(first.meeting().id());
        assertThat(meetings.count()).isEqualTo(1);
        assertThat(tasks.count()).isEqualTo(2);
        verify(agent, times(1)).extract(anyString());
    }
}
