package com.meet2action.backend.agent;

import org.springframework.http.HttpStatus;

/** The agent service could not produce a valid extraction. Message is safe to show to clients. */
public class AgentException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AgentException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public AgentException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
