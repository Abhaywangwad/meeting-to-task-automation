package com.meet2action.backend;

import com.meet2action.backend.agent.AgentExtractResponse;
import com.meet2action.backend.agent.AgentExtractResponse.Statement;
import com.meet2action.backend.agent.AgentExtractResponse.Task;
import java.util.List;

final class TestData {

    private TestData() {}

    static final String QUOTE = "Priya, please prepare the test report by Friday.";
    static final String TRANSCRIPT =
            "Alex: " + QUOTE + "\nPriya: Sure, I will do it.\nAlex: Someone should update the docs.\n"
                    + "Alex: We decided to ship version 2 next sprint.\nBob: I finished the login page yesterday.";

    static Task verifiedTask() {
        int start = TRANSCRIPT.indexOf(QUOTE);
        return new Task("agent-1", "Prepare test report", "Priya prepares the test report", "Priya", "Friday",
                QUOTE, true, "verified", start, start + QUOTE.length(), "exact", 0.9);
    }

    /** Owner and deadline are null: the transcript does not name them. Evidence is NOT verified. */
    static Task unverifiedTask() {
        return new Task("agent-2", "Update docs", "", null, null,
                "Someone must rewrite all docs", false, "unverified", null, null, null, null);
    }

    static Statement statement(String text, String quote) {
        int start = TRANSCRIPT.indexOf(quote);
        return new Statement("s", text, null, quote, true, "verified", start, start + quote.length(), "exact");
    }

    static AgentExtractResponse response() {
        return new AgentExtractResponse(
                List.of(verifiedTask(), unverifiedTask()),
                List.of(statement("Ship v2 next sprint", "We decided to ship version 2 next sprint.")),
                List.of(statement("Who updates docs?", "Someone should update the docs.")),
                List.of(statement("Login page done", "I finished the login page yesterday.")));
    }

    static AgentExtractResponse emptyResponse() {
        return new AgentExtractResponse(List.of(), List.of(), List.of(), List.of());
    }
}
