package com.meet2action.backend.trello;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Talks to the Trello REST API. Credentials travel only in the Authorization header (never in URLs),
 * and nothing credential-related or from response bodies is logged.
 */
public class HttpTrelloClient implements TrelloClient {

    private static final Logger log = LoggerFactory.getLogger(HttpTrelloClient.class);
    private static final int MAX_DESC = 16_000;

    private record Raw(int status, String body) {}

    private final RestClient restClient;
    private final TrelloProperties props;
    private final ObjectMapper mapper;

    public HttpTrelloClient(RestClient restClient, TrelloProperties props, ObjectMapper mapper) {
        this.restClient = restClient;
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public Card createCard(String listId, String name, String description, String dueIso) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idList", listId);
        body.put("name", name);
        body.put("desc", description.length() > MAX_DESC ? description.substring(0, MAX_DESC) : description);
        if (dueIso != null) {
            body.put("due", dueIso);
        }
        Raw raw = send(() -> restClient.post().uri("/cards").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", authHeader()).accept(MediaType.APPLICATION_JSON).body(body)
                .exchange((req, res) -> new Raw(res.getStatusCode().value(), readBody(res.getBody()))));
        checkStatus(raw.status());
        JsonNode json = parse(raw.body());
        String id = text(json, "id");
        String url = text(json, "url");
        if (id == null || url == null) {
            log.warn("Trello create-card answered {} without id/url", raw.status());
            throw new TrelloException(TrelloException.Kind.MALFORMED, "Trello answered without a card id or URL.");
        }
        return new Card(id, url);
    }

    @Override
    public void moveCard(String cardId, String listId) {
        Map<String, Object> body = Map.of("idList", listId);
        Raw raw = send(() -> restClient.put().uri("/cards/{id}", cardId).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", authHeader()).accept(MediaType.APPLICATION_JSON).body(body)
                .exchange((req, res) -> new Raw(res.getStatusCode().value(), readBody(res.getBody()))));
        checkStatus(raw.status());
        JsonNode json = parse(raw.body());
        // Trust only a reply that names the card and the list it is now in.
        if (!cardId.equals(text(json, "id")) || !listId.equals(text(json, "idList"))) {
            log.warn("Trello move answered {} but the card/list in the reply do not match the request", raw.status());
            throw new TrelloException(TrelloException.Kind.MALFORMED,
                    "Trello answered the move without confirming the new list.");
        }
    }

    @Override
    public Optional<Card> findCardByMarker(String listId, String marker) {
        Raw raw = send(() -> restClient.get().uri("/lists/{id}/cards?fields=id,url,desc", listId)
                .header("Authorization", authHeader()).accept(MediaType.APPLICATION_JSON)
                .exchange((req, res) -> new Raw(res.getStatusCode().value(), readBody(res.getBody()))));
        checkStatus(raw.status());
        JsonNode json = parse(raw.body());
        if (json == null || !json.isArray()) {
            throw new TrelloException(TrelloException.Kind.MALFORMED, "Trello answered with an unexpected list format.");
        }
        for (JsonNode card : json) {
            String desc = text(card, "desc");
            if (desc != null && desc.contains(marker)) {
                String id = text(card, "id");
                String url = text(card, "url");
                if (id != null && url != null) {
                    return Optional.of(new Card(id, url));
                }
            }
        }
        return Optional.empty();
    }

    private String authHeader() {
        return "OAuth oauth_consumer_key=\"" + props.getApiKey() + "\", oauth_token=\"" + props.getToken() + "\"";
    }

    private Raw send(java.util.function.Supplier<Raw> call) {
        try {
            return call.get();
        } catch (ResourceAccessException e) {
            Throwable c = e.getCause();
            boolean neverSent = c instanceof ConnectException || c instanceof UnknownHostException
                    || c instanceof NoRouteToHostException
                    || (c instanceof SocketTimeoutException && String.valueOf(c.getMessage()).toLowerCase().contains("connect"));
            if (neverSent) {
                log.warn("Trello unreachable ({})", c.getClass().getSimpleName());
                throw new TrelloException(TrelloException.Kind.UNREACHABLE, "Could not connect to Trello.", e);
            }
            log.warn("Trello request failed after being sent ({})", c == null ? "io" : c.getClass().getSimpleName());
            throw new TrelloException(TrelloException.Kind.TIMEOUT,
                    "Trello did not answer in time; the outcome is unknown.", e);
        }
    }

    private void checkStatus(int status) {
        if (status >= 200 && status < 300) {
            return;
        }
        log.warn("Trello answered HTTP {}", status);
        switch (status) {
            case 401, 403 -> throw new TrelloException(TrelloException.Kind.AUTH, "Trello rejected the credentials (HTTP " + status + ").");
            case 400, 404 -> throw new TrelloException(TrelloException.Kind.LIST_INVALID,
                    "Trello could not use the destination list (HTTP " + status + ").");
            case 429 -> throw new TrelloException(TrelloException.Kind.RATE_LIMITED, "Trello rate limit reached (HTTP 429).");
            default -> {
                if (status >= 500) {
                    throw new TrelloException(TrelloException.Kind.SERVER_ERROR, "Trello failed with HTTP " + status + "; the outcome is unknown.");
                }
                throw new TrelloException(TrelloException.Kind.REJECTED, "Trello rejected the request (HTTP " + status + ").");
            }
        }
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? null : mapper.readTree(body);
        } catch (Exception e) {
            throw new TrelloException(TrelloException.Kind.MALFORMED, "Trello answered with invalid JSON.");
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }

    private static String readBody(java.io.InputStream in) throws java.io.IOException {
        return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
