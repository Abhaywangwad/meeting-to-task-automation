package com.meet2action.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.meet2action.backend.domain.EvidenceStatus;
import com.meet2action.backend.domain.Meeting;
import com.meet2action.backend.domain.TaskProposal;
import com.meet2action.backend.trello.TrelloCardContent;
import org.junit.jupiter.api.Test;

class TrelloCardContentTest {

    private static TaskProposal task(String owner, String deadline, String quote) {
        return new TaskProposal(new Meeting("t", "h"), 0, "Title", "Some description", owner, deadline, quote,
                EvidenceStatus.VERIFIED, 0, 5, "exact", null);
    }

    @Test
    void buildsReadableDescriptionWithEvidenceAndStableMarker() {
        TaskProposal t = task("Priya", "Friday", "line one\nline two");
        var c = TrelloCardContent.build(t);
        assertThat(c.name()).isEqualTo("Title");
        assertThat(c.description()).startsWith("Some description")
                .contains("**Owner:** Priya", "**Deadline:** Friday", "**Meeting evidence**", "> line one", "> line two")
                .endsWith(TrelloCardContent.marker(t.getId()));
    }

    @Test
    void markerIsExactAndTaskSpecific() {
        assertThat(TrelloCardContent.marker("abc")).isEqualTo("[meet2action-task-id:abc]");
        assertThat(TrelloCardContent.marker("abc")).isNotEqualTo(TrelloCardContent.marker("abcd"));
    }

    @Test
    void missingOwnerAndDeadlineAreOmittedNotInvented() {
        var c = TrelloCardContent.build(task(null, null, "q"));
        assertThat(c.description()).doesNotContain("Owner", "Deadline");
        assertThat(c.dueIso()).isNull();
    }

    @Test
    void onlyExplicitIsoDatesBecomeDueDates() {
        assertThat(TrelloCardContent.build(task(null, "2030-01-15", "q")).dueIso()).isEqualTo("2030-01-15T12:00:00.000Z");
        assertThat(TrelloCardContent.build(task(null, "2030-01-15T09:30:00+02:00", "q")).dueIso())
                .isEqualTo("2030-01-15T07:30:00.000Z");
        assertThat(TrelloCardContent.build(task(null, "2030-01-15T09:30:00Z", "q")).dueIso())
                .isEqualTo("2030-01-15T09:30:00.000Z");
        for (String ambiguous : new String[]{"Friday", "next week", "end of month", "3/5", "2030-13-45", "soon", ""}) {
            assertThat(TrelloCardContent.build(task(null, ambiguous, "q")).dueIso()).as(ambiguous).isNull();
        }
    }

    @Test
    void veryLongContentIsTruncatedButKeepsTheMarker() {
        TaskProposal t = task("A", null, "x".repeat(40_000));
        var c = TrelloCardContent.build(t);
        assertThat(c.description().length()).isLessThan(16_000);
        assertThat(c.description()).endsWith(TrelloCardContent.marker(t.getId()));
    }
}
