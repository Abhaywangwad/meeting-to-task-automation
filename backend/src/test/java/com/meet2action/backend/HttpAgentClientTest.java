package com.meet2action.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.meet2action.backend.agent.AgentException;
import com.meet2action.backend.agent.AgentExtractResponse;
import com.meet2action.backend.agent.HttpAgentClient;
import jakarta.validation.Validation;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Unit tests of the agent client against a mocked HTTP server. No FastAPI, no network, no LLM. */
class HttpAgentClientTest {

    private static final String URL = "http://agent.test/extract";
    private static final String T = TestData.TRANSCRIPT;

    private MockRestServiceServer server;
    private HttpAgentClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://agent.test");
        server = MockRestServiceServer.bindTo(builder).build();
        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        client = new HttpAgentClient(builder.build(),
                Validation.buildDefaultValidatorFactory().getValidator(), mapper);
    }

    private static String task(String evidenceStatus, boolean verified, String offsets) {
        return """
                {"id":"x","title":"Do it","description":"d","owner":null,"deadline":null,
                 "evidence_quote":"Priya, please prepare","evidence_verified":%s,"evidence_status":"%s",
                 %s "evidence_match":"exact","confidence":0.5,"extra_unknown_field":1}""".formatted(
                verified, evidenceStatus, offsets);
    }

    private static String body(String tasksJson) {
        return "{\"tasks\":[" + tasksJson + "],\"decisions\":[],\"unresolved_questions\":[],\"completed_updates\":[]}";
    }

    @Test
    void sendsTranscriptAndParsesActualFastApiShape() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.transcript").value(T))
                .andRespond(withSuccess(body(task("verified", true, "\"evidence_start\":7,\"evidence_end\":28,")),
                        MediaType.APPLICATION_JSON));
        AgentExtractResponse r = client.extract(T);
        assertThat(r.tasks()).hasSize(1);
        assertThat(r.tasks().get(0).owner()).isNull();
        assertThat(r.tasks().get(0).evidenceStart()).isEqualTo(7);
        server.verify();
    }

    @Test
    void agentErrorCodeIsPassedThroughForKnownStatuses() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"detail\":{\"code\":\"llm_not_configured\",\"message\":\"secret details\"}}"));
        assertThatThrownBy(() -> client.extract(T)).isInstanceOfSatisfying(AgentException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.getCode()).isEqualTo("llm_not_configured");
            assertThat(e.getMessage()).doesNotContain("secret details");
        });
    }

    @Test
    void unexpectedAgentStatusBecomesBadGateway() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("boom"));
        assertThatThrownBy(() -> client.extract(T)).isInstanceOfSatisfying(AgentException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(e.getCode()).isEqualTo("agent_http_error");
        });
    }

    @Test
    void timeoutBecomesGatewayTimeout() {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("read timed out")));
        assertThatThrownBy(() -> client.extract(T)).isInstanceOfSatisfying(AgentException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
            assertThat(e.getCode()).isEqualTo("agent_timeout");
        });
    }

    @Test
    void connectionFailureBecomesServiceUnavailable() {
        server.expect(requestTo(URL)).andRespond(withException(new ConnectException("refused")));
        assertThatThrownBy(() -> client.extract(T)).isInstanceOfSatisfying(AgentException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.getCode()).isEqualTo("agent_unavailable");
        });
    }

    @Test
    void malformedBodiesAreRejected() {
        for (String bad : new String[]{"not json", "[]", "{\"tasks\":\"nope\"}", "{}",
                "{\"tasks\":[{\"description\":\"no title\"}],\"decisions\":[],\"unresolved_questions\":[],\"completed_updates\":[]}"}) {
            server.reset();
            server.expect(requestTo(URL)).andRespond(withSuccess(bad, MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> client.extract(T))
                    .as(bad)
                    .isInstanceOfSatisfying(AgentException.class,
                            e -> assertThat(e.getCode()).isEqualTo("agent_malformed_response"));
        }
    }

    @Test
    void contradictoryEvidenceFlagsAreRejected() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                body(task("unverified", true, "")), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.extract(T)).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getCode()).isEqualTo("agent_malformed_response"));
    }

    @Test
    void offsetsOutsideTranscriptAreRejected() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                body(task("verified", true, "\"evidence_start\":5,\"evidence_end\":999999,")),
                MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.extract(T)).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getCode()).isEqualTo("agent_malformed_response"));
    }

    @Test
    void validEmptyResultIsAccepted() {
        server.expect(requestTo(URL)).andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess(body(""), MediaType.APPLICATION_JSON));
        assertThat(client.extract(T).tasks()).isEmpty();
    }
}
