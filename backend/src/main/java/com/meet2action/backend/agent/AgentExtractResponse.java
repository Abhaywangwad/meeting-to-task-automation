package com.meet2action.backend.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Mirrors the FastAPI POST /extract response (agent-service/app/extraction/schemas.py).
 * JSON is snake_case, mapped by the global naming strategy. Unknown fields are tolerated
 * so the agent can add fields without breaking the backend.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentExtractResponse(
        @NotNull List<@NotNull @Valid Task> tasks,
        @NotNull List<@NotNull @Valid Statement> decisions,
        @NotNull List<@NotNull @Valid Statement> unresolvedQuestions,
        @NotNull List<@NotNull @Valid Statement> completedUpdates) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Task(
            String id,
            @NotBlank String title,
            String description,
            String owner,
            String deadline,
            @NotNull String evidenceQuote,
            @NotNull Boolean evidenceVerified,
            @NotBlank String evidenceStatus,
            Integer evidenceStart,
            Integer evidenceEnd,
            String evidenceMatch,
            @DecimalMin("0.0") @DecimalMax("1.0") Double confidence) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Statement(
            String id,
            @NotBlank String text,
            String owner,
            @NotNull String evidenceQuote,
            @NotNull Boolean evidenceVerified,
            @NotBlank String evidenceStatus,
            Integer evidenceStart,
            Integer evidenceEnd,
            String evidenceMatch) {}
}
