package com.meet2action.backend.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingInsightRepository extends JpaRepository<MeetingInsight, String> {

    List<MeetingInsight> findByMeetingIdOrderByTypeAscPositionAsc(String meetingId);
}
