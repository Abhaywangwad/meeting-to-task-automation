package com.meet2action.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meet2action.backend.trello.HttpTrelloClient;
import com.meet2action.backend.trello.TrelloClient;
import com.meet2action.backend.trello.TrelloException;
import com.meet2action.backend.trello.TrelloException.Kind;
import com.meet2action.backend.trello.TrelloProperties;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The real Trello client against a mocked HTTP server. Nothing here can reach api.trello.com. */
class HttpTrelloClientTest {

    private static final String BASE = "https://trello.test/1";

    private MockRestServiceServer server;
    private TrelloClient client;

    @BeforeEach
    void setUp() {
        TrelloProperties props = new TrelloProperties();
        props.setApiKey("KEY123");
        props.setToken("TOKEN456");
        props.setListId("LIST1");
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpTrelloClient(builder.build(), props, new ObjectMapper());
    }

    @Test
    void createsCardWithListNameDescriptionAndDueAndKeepsCredentialsOutOfTheUrl() {
        server.expect(requestTo(BASE + "/cards")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "OAuth oauth_consumer_key=\"KEY123\", oauth_token=\"TOKEN456\""))
                .andExpect(jsonPath("$.idList").value("LIST1"))
                .andExpect(jsonPath("$.name").value("My task"))
                .andExpect(jsonPath("$.desc").value("body [meet2action-task-id:x]"))
                .andExpect(jsonPath("$.due").value("2030-01-15T12:00:00.000Z"))
                .andRespond(withSuccess("{\"id\":\"c1\",\"url\":\"https://trello.com/c/x\",\"extra\":1}", MediaType.APPLICATION_JSON));

        TrelloClient.Card card = client.createCard("LIST1", "My task", "body [meet2action-task-id:x]", "2030-01-15T12:00:00.000Z");

        assertThat(card).isEqualTo(new TrelloClient.Card("c1", "https://trello.com/c/x"));
        server.verify();
    }

    @Test
    void omitsDueWhenNotGiven() {
        server.expect(requestTo(BASE + "/cards")).andExpect(jsonPath("$.due").doesNotExist())
                .andRespond(withSuccess("{\"id\":\"c1\",\"url\":\"https://trello.com/c/x\"}", MediaType.APPLICATION_JSON));
        client.createCard("LIST1", "n", "d", null);
        server.verify();
    }

    private Kind createFailureKind(HttpStatus status) {
        server.reset();
        server.expect(requestTo(BASE + "/cards")).andRespond(withStatus(status).body("secret-ish provider body"));
        return failure(() -> client.createCard("LIST1", "n", "d", null)).getKind();
    }

    private static TrelloException failure(Runnable r) {
        return (TrelloException) catchThrowable(r);
    }

    private static Throwable catchThrowable(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            return t;
        }
        throw new AssertionError("expected an exception");
    }

    @Test
    void mapsHttpStatusesTruthfully() {
        assertThat(createFailureKind(HttpStatus.UNAUTHORIZED)).isEqualTo(Kind.AUTH);
        assertThat(createFailureKind(HttpStatus.FORBIDDEN)).isEqualTo(Kind.AUTH);
        assertThat(createFailureKind(HttpStatus.NOT_FOUND)).isEqualTo(Kind.LIST_INVALID);
        assertThat(createFailureKind(HttpStatus.BAD_REQUEST)).isEqualTo(Kind.LIST_INVALID);
        assertThat(createFailureKind(HttpStatus.TOO_MANY_REQUESTS)).isEqualTo(Kind.RATE_LIMITED);
        assertThat(createFailureKind(HttpStatus.CONFLICT)).isEqualTo(Kind.REJECTED);
        assertThat(createFailureKind(HttpStatus.INTERNAL_SERVER_ERROR)).isEqualTo(Kind.SERVER_ERROR);
    }

    @Test
    void definiteFailuresSayNoCardExistsAndAmbiguousOnesSayItMay() {
        assertThat(new TrelloException(Kind.AUTH, "x").cardMayExist()).isFalse();
        assertThat(new TrelloException(Kind.LIST_INVALID, "x").cardMayExist()).isFalse();
        assertThat(new TrelloException(Kind.RATE_LIMITED, "x").cardMayExist()).isFalse();
        assertThat(new TrelloException(Kind.UNREACHABLE, "x").cardMayExist()).isFalse();
        assertThat(new TrelloException(Kind.SERVER_ERROR, "x").cardMayExist()).isTrue();
        assertThat(new TrelloException(Kind.TIMEOUT, "x").cardMayExist()).isTrue();
        assertThat(new TrelloException(Kind.MALFORMED, "x").cardMayExist()).isTrue();
    }

    @Test
    void errorMessagesNeverContainResponseBodiesOrCredentials() {
        server.expect(requestTo(BASE + "/cards")).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("invalid key KEY123"));
        assertThatThrownBy(() -> client.createCard("LIST1", "n", "d", null))
                .hasMessageNotContaining("KEY123").hasMessageNotContaining("TOKEN456").hasMessageNotContaining("invalid key");
    }

    @Test
    void readTimeoutIsAnUnknownOutcomeButConnectFailureIsNot() {
        server.expect(requestTo(BASE + "/cards")).andRespond(withException(new SocketTimeoutException("Read timed out")));
        assertThat(failure(() -> client.createCard("LIST1", "n", "d", null)).getKind()).isEqualTo(Kind.TIMEOUT);

        server.reset();
        server.expect(requestTo(BASE + "/cards")).andRespond(withException(new ConnectException("Connection refused")));
        assertThat(failure(() -> client.createCard("LIST1", "n", "d", null)).getKind()).isEqualTo(Kind.UNREACHABLE);

        server.reset();
        server.expect(requestTo(BASE + "/cards")).andRespond(withException(new SocketTimeoutException("Connect timed out")));
        assertThat(failure(() -> client.createCard("LIST1", "n", "d", null)).getKind()).isEqualTo(Kind.UNREACHABLE);
    }

    @Test
    void successfulReplyWithoutIdOrUrlIsMalformedNotSuccess() {
        for (String body : new String[]{"{}", "{\"id\":\"c1\"}", "not json", ""}) {
            server.reset();
            server.expect(requestTo(BASE + "/cards")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertThat(failure(() -> client.createCard("LIST1", "n", "d", null)).getKind())
                    .as(body).isEqualTo(Kind.MALFORMED);
        }
    }

    @Test
    void movesAnExistingCardWithPutAndNeverPostsANewOne() {
        server.expect(requestTo(BASE + "/cards/card9")).andExpect(method(HttpMethod.PUT))
                .andExpect(header("Authorization", "OAuth oauth_consumer_key=\"KEY123\", oauth_token=\"TOKEN456\""))
                .andExpect(jsonPath("$.idList").value("LISTB"))
                .andRespond(withSuccess("{\"id\":\"card9\",\"idList\":\"LISTB\",\"name\":\"x\"}", MediaType.APPLICATION_JSON));
        client.moveCard("card9", "LISTB");
        server.verify();
    }

    @Test
    void aMoveReplyThatDoesNotConfirmTheNewListIsNotSuccess() {
        for (String body : new String[]{"{\"id\":\"card9\",\"idList\":\"OTHER\"}", "{\"id\":\"zzz\",\"idList\":\"LISTB\"}", "{}", "nope"}) {
            server.reset();
            server.expect(requestTo(BASE + "/cards/card9")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertThat(failure(() -> client.moveCard("card9", "LISTB")).getKind()).as(body).isEqualTo(Kind.MALFORMED);
        }
    }

    @Test
    void moveErrorsAreMappedTruthfully() {
        record Case(HttpStatus status, Kind kind) {}
        for (Case c : new Case[]{new Case(HttpStatus.UNAUTHORIZED, Kind.AUTH), new Case(HttpStatus.NOT_FOUND, Kind.LIST_INVALID),
                new Case(HttpStatus.TOO_MANY_REQUESTS, Kind.RATE_LIMITED), new Case(HttpStatus.BAD_GATEWAY, Kind.SERVER_ERROR)}) {
            server.reset();
            server.expect(requestTo(BASE + "/cards/card9")).andRespond(withStatus(c.status()));
            assertThat(failure(() -> client.moveCard("card9", "LISTB")).getKind()).isEqualTo(c.kind());
        }
        server.reset();
        server.expect(requestTo(BASE + "/cards/card9")).andRespond(withException(new SocketTimeoutException("Read timed out")));
        assertThat(failure(() -> client.moveCard("card9", "LISTB")).getKind()).isEqualTo(Kind.TIMEOUT);
    }

    @Test
    void findsCardByExactMarkerOnTheList() {
        String marker = "[meet2action-task-id:abc]";
        server.expect(requestTo(BASE + "/lists/LIST1/cards?fields=id,url,desc")).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "OAuth oauth_consumer_key=\"KEY123\", oauth_token=\"TOKEN456\""))
                .andRespond(withSuccess("""
                        [{"id":"c1","url":"https://trello.com/c/1","desc":"unrelated"},
                         {"id":"c2","url":"https://trello.com/c/2","desc":"x [meet2action-task-id:abcd] similar id"},
                         {"id":"c3","url":"https://trello.com/c/3","desc":"mine %s"}]""".formatted(marker),
                        MediaType.APPLICATION_JSON));
        Optional<TrelloClient.Card> found = client.findCardByMarker("LIST1", marker);
        assertThat(found).contains(new TrelloClient.Card("c3", "https://trello.com/c/3"));
    }

    @Test
    void noMarkerMatchMeansEmptyAndListErrorsAreReported() {
        server.expect(requestTo(BASE + "/lists/LIST1/cards?fields=id,url,desc"))
                .andRespond(withSuccess("[{\"id\":\"c1\",\"url\":\"u\",\"desc\":\"nothing\"}]", MediaType.APPLICATION_JSON));
        assertThat(client.findCardByMarker("LIST1", "[meet2action-task-id:zzz]")).isEmpty();

        server.reset();
        server.expect(requestTo(BASE + "/lists/LIST1/cards?fields=id,url,desc")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThat(failure(() -> client.findCardByMarker("LIST1", "m")).getKind()).isEqualTo(Kind.AUTH);

        server.reset();
        server.expect(requestTo(BASE + "/lists/LIST1/cards?fields=id,url,desc")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThat(failure(() -> client.findCardByMarker("LIST1", "m")).getKind()).isEqualTo(Kind.MALFORMED);
    }
}
