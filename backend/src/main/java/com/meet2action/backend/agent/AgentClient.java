package com.meet2action.backend.agent;

public interface AgentClient {

    /**
     * Sends the transcript to the agent service and returns a validated result.
     *
     * @throws AgentException if the agent is unreachable, times out, errors, or returns an invalid result
     */
    AgentExtractResponse extract(String transcript);
}
