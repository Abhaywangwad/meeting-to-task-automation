package com.meet2action.backend.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.ResourceAccessException;

/** Calls the FastAPI service. Never talks to an LLM provider and never holds LLM credentials. */
public class HttpAgentClient implements AgentClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAgentClient.class);

    private final RestClient restClient;
    private final Validator validator;
    private final ObjectMapper objectMapper;

    public HttpAgentClient(RestClient restClient, Validator validator, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentExtractResponse extract(String transcript) {
        String body;
        try {
            body = restClient.post()
                    .uri("/extract")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("transcript", transcript))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw errorFromStatus(res.getStatusCode(), new String(res.getBody().readAllBytes()));
                    })
                    .body(String.class);
        } catch (AgentException e) {
            throw e;
        } catch (ResourceAccessException e) {
            if (hasCause(e, SocketTimeoutException.class)) {
                log.warn("Agent service timed out");
                throw new AgentException(HttpStatus.GATEWAY_TIMEOUT, "agent_timeout",
                        "The agent service timed out while extracting.", e);
            }
            log.warn("Agent service unreachable: {}", e.getMessage());
            throw new AgentException(HttpStatus.SERVICE_UNAVAILABLE, "agent_unavailable",
                    "The agent service is unreachable.", e);
        } catch (RestClientException e) {
            log.warn("Agent call failed: {}", e.getMessage());
            throw new AgentException(HttpStatus.BAD_GATEWAY, "agent_error",
                    "The agent service call failed.", e);
        }

        return parseAndValidate(body, transcript);
    }

    private AgentExtractResponse parseAndValidate(String body, String transcript) {
        if (body == null || body.isBlank()) {
            throw malformed("empty response body");
        }
        AgentExtractResponse response;
        try {
            response = objectMapper.readValue(body, AgentExtractResponse.class);
        } catch (Exception e) {
            throw malformed("response is not valid JSON of the expected shape");
        }
        Set<ConstraintViolation<AgentExtractResponse>> violations = validator.validate(response);
        if (!violations.isEmpty()) {
            ConstraintViolation<AgentExtractResponse> v = violations.iterator().next();
            throw malformed(v.getPropertyPath() + " " + v.getMessage());
        }
        for (AgentExtractResponse.Task t : response.tasks()) {
            checkEvidence(t.evidenceStatus(), t.evidenceVerified(), t.evidenceStart(), t.evidenceEnd(), transcript);
        }
        List<AgentExtractResponse.Statement> statements = new java.util.ArrayList<>();
        statements.addAll(response.decisions());
        statements.addAll(response.unresolvedQuestions());
        statements.addAll(response.completedUpdates());
        for (AgentExtractResponse.Statement s : statements) {
            checkEvidence(s.evidenceStatus(), s.evidenceVerified(), s.evidenceStart(), s.evidenceEnd(), transcript);
        }
        return response;
    }

    /** Cross-field checks: status must agree with the boolean, offsets must lie inside the transcript. */
    private void checkEvidence(String status, boolean verified, Integer start, Integer end, String transcript) {
        String s = status.toLowerCase(Locale.ROOT);
        if (!s.equals("verified") && !s.equals("unverified")) {
            throw malformed("unknown evidence_status");
        }
        if (s.equals("verified") != verified) {
            throw malformed("evidence_status and evidence_verified disagree");
        }
        if ((start == null) != (end == null)) {
            throw malformed("evidence offsets must be given together");
        }
        if (start != null && (start < 0 || end <= start || end > transcript.length())) {
            throw malformed("evidence offsets are outside the transcript");
        }
    }

    private AgentException malformed(String detail) {
        log.warn("Agent returned an invalid extraction result: {}", detail);
        return new AgentException(HttpStatus.BAD_GATEWAY, "agent_malformed_response",
                "The agent service returned an invalid extraction result.");
    }

    private AgentException errorFromStatus(HttpStatusCode status, String body) {
        String agentCode = null;
        try {
            JsonNode code = objectMapper.readTree(body).path("detail").path("code");
            if (code.isTextual()) {
                agentCode = code.asText();
            }
        } catch (Exception ignored) {
            // body was not the expected JSON; fall through to the generic code
        }
        int s = status.value();
        HttpStatus mapped = (s == 429 || s == 503 || s == 504) ? HttpStatus.valueOf(s) : HttpStatus.BAD_GATEWAY;
        String code = agentCode != null && agentCode.matches("[a-z_]{1,64}") ? agentCode : "agent_http_error";
        log.warn("Agent service responded with HTTP {} (code={})", s, code);
        return new AgentException(mapped, code, "The agent service returned HTTP " + s
                + (agentCode != null ? " (" + code + ")" : "") + ".");
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }
}
