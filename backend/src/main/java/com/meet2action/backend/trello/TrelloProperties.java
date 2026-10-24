package com.meet2action.backend.trello;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Backend-only Trello settings. Deliberately has no toString and no getters used by any DTO, so the
 * key and token cannot leak through logging or API responses.
 */
@ConfigurationProperties(prefix = "app.trello")
public class TrelloProperties {

    private String apiKey = "";
    private String token = "";
    private String listId = "";
    private String baseUrl = "https://api.trello.com/1";
    private long connectTimeoutSeconds = 5;
    private long readTimeoutSeconds = 15;
    private long staleSyncSeconds = 120;

    public boolean isConfigured() {
        return !apiKey.isBlank() && !token.isBlank() && !listId.isBlank();
    }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey.trim(); }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token == null ? "" : token.trim(); }
    public String getListId() { return listId; }
    public void setListId(String listId) { this.listId = listId == null ? "" : listId.trim(); }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public long getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(long v) { this.connectTimeoutSeconds = v; }
    public long getReadTimeoutSeconds() { return readTimeoutSeconds; }
    public void setReadTimeoutSeconds(long v) { this.readTimeoutSeconds = v; }
    /** A SYNCING attempt older than this is considered abandoned and may be reconciled. */
    public long getStaleSyncSeconds() { return staleSyncSeconds; }
    public void setStaleSyncSeconds(long v) { this.staleSyncSeconds = v; }

    @Override
    public String toString() {
        return "TrelloProperties[configured=" + isConfigured() + "]";
    }
}
