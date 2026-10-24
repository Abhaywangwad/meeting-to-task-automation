package com.meet2action.backend.trello;

import com.meet2action.backend.domain.TaskProposal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Builds the card from the approved task. Never invents owner, deadline or due date. */
public final class TrelloCardContent {

    public record Content(String name, String description, String dueIso) {}

    private static final int MAX_BODY = 15_000;
    private static final DateTimeFormatter DUE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private TrelloCardContent() {}

    /** Stable, exact marker used to find this task's card again after an ambiguous result. */
    public static String marker(String taskId) {
        return "[meet2action-task-id:" + taskId + "]";
    }

    public static Content build(TaskProposal t) {
        StringBuilder body = new StringBuilder();
        if (t.getDescription() != null && !t.getDescription().isBlank()) {
            body.append(t.getDescription().trim()).append("\n\n");
        }
        if (t.getOwner() != null) {
            body.append("**Owner:** ").append(t.getOwner()).append('\n');
        }
        if (t.getDeadline() != null) {
            body.append("**Deadline:** ").append(t.getDeadline()).append('\n');
        }
        if (t.getOwner() != null || t.getDeadline() != null) {
            body.append('\n');
        }
        body.append("**Meeting evidence**\n");
        for (String line : t.getEvidenceQuote().split("\\R")) {
            body.append("> ").append(line).append('\n');
        }

        String text = body.length() > MAX_BODY ? body.substring(0, MAX_BODY) + "\n…" : body.toString();
        String description = text + "\n---\nCreated by Meet2Action. " + marker(t.getId());
        return new Content(t.getTitle(), description, parseDue(t.getDeadline()));
    }

    /**
     * Only an explicit ISO date (2026-03-05) or ISO date-time with offset is accepted. Anything else
     * ("Friday", "next week", "3/5") is ambiguous and yields no due date. A bare date means 12:00 UTC.
     */
    static String parseDue(String deadline) {
        if (deadline == null) {
            return null;
        }
        String d = deadline.trim();
        try {
            return DUE.format(LocalDate.parse(d).atTime(12, 0).toInstant(ZoneOffset.UTC).atOffset(ZoneOffset.UTC));
        } catch (DateTimeParseException ignored) {
            // not a plain date
        }
        try {
            return DUE.format(OffsetDateTime.parse(d).toInstant().atOffset(ZoneOffset.UTC));
        } catch (DateTimeParseException ignored) {
            // not an offset date-time
        }
        try {
            return DUE.format(Instant.parse(d).atOffset(ZoneOffset.UTC));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
