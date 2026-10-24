package com.meet2action.backend.trello;

import com.meet2action.backend.domain.WorkStatus;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Backend-only Trello settings. Deliberately has no toString that shows values and no getters used by any DTO,
 * so the key and token cannot leak through logging or API responses.
 *
 * <p>Lists: TRELLO_TODO_LIST_ID (falls back to the legacy TRELLO_LIST_ID), TRELLO_IN_PROGRESS_LIST_ID and
 * TRELLO_COMPLETED_LIST_ID. Creating cards needs only the To Do list; moving cards also needs the other two.
 */
@ConfigurationProperties(prefix = "app.trello")
public class TrelloProperties {

    private String apiKey = "";
    private String token = "";
    /** Legacy single-list setting; used as the To Do list when todoListId is empty. */
    private String listId = "";
    private String todoListId = "";
    private String inProgressListId = "";
    private String completedListId = "";
    private String baseUrl = "https://api.trello.com/1";
    private long connectTimeoutSeconds = 5;
    private long readTimeoutSeconds = 15;
    private long staleSyncSeconds = 120;

    /** Credentials plus the list new cards are created in. */
    public boolean isConfigured() {
        return !apiKey.isBlank() && !token.isBlank() && !effectiveTodoListId().isBlank();
    }

    /** Whether cards can be moved between all three work lists. */
    public boolean isWorkListsConfigured() {
        return isConfigured() && !inProgressListId.isBlank() && !completedListId.isBlank();
    }

    public String effectiveTodoListId() {
        return todoListId.isBlank() ? listId : todoListId;
    }

    /** The Trello list for a work status, or empty when that list is not configured. */
    public String listFor(WorkStatus status) {
        return switch (status) {
            case TO_DO -> effectiveTodoListId();
            case IN_PROGRESS -> inProgressListId;
            case COMPLETED -> completedListId;
        };
    }

    /** Name of the environment variable that configures the list for a status (for error messages). */
    public static String variableFor(WorkStatus status) {
        return switch (status) {
            case TO_DO -> "TRELLO_TODO_LIST_ID";
            case IN_PROGRESS -> "TRELLO_IN_PROGRESS_LIST_ID";
            case COMPLETED -> "TRELLO_COMPLETED_LIST_ID";
        };
    }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { this.apiKey = clean(v); }
    public String getToken() { return token; }
    public void setToken(String v) { this.token = clean(v); }
    public String getListId() { return listId; }
    public void setListId(String v) { this.listId = clean(v); }
    public String getTodoListId() { return todoListId; }
    public void setTodoListId(String v) { this.todoListId = clean(v); }
    public String getInProgressListId() { return inProgressListId; }
    public void setInProgressListId(String v) { this.inProgressListId = clean(v); }
    public String getCompletedListId() { return completedListId; }
    public void setCompletedListId(String v) { this.completedListId = clean(v); }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public long getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(long v) { this.connectTimeoutSeconds = v; }
    public long getReadTimeoutSeconds() { return readTimeoutSeconds; }
    public void setReadTimeoutSeconds(long v) { this.readTimeoutSeconds = v; }
    /** A SYNCING attempt (or an unfinished list move) older than this is considered abandoned. */
    public long getStaleSyncSeconds() { return staleSyncSeconds; }
    public void setStaleSyncSeconds(long v) { this.staleSyncSeconds = v; }

    private static String clean(String v) {
        return v == null ? "" : v.trim();
    }

    @Override
    public String toString() {
        return "TrelloProperties[configured=" + isConfigured() + ", workLists=" + isWorkListsConfigured() + "]";
    }
}
