package com.meet2action.backend.trello;

/** A failed Trello call. The message never contains credentials, URLs with credentials, or response bodies. */
public class TrelloException extends RuntimeException {

    public enum Kind {
        /** 401/403: bad key or token. No card created. */
        AUTH(false),
        /** 400/404: the destination list is invalid or not accessible. No card created. */
        LIST_INVALID(false),
        /** 429. No card created. */
        RATE_LIMITED(false),
        /** Could not connect at all (refused, DNS, connect timeout). No card created. */
        UNREACHABLE(false),
        /** Other 4xx. No card created. */
        REJECTED(false),
        /** 5xx: Trello may have created the card before failing. */
        SERVER_ERROR(true),
        /** The request was sent but no answer arrived in time. The card may exist. */
        TIMEOUT(true),
        /** A 2xx answer we could not understand. The card probably exists. */
        MALFORMED(true);

        private final boolean cardMayExist;

        Kind(boolean cardMayExist) {
            this.cardMayExist = cardMayExist;
        }
    }

    private final Kind kind;

    public TrelloException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public TrelloException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() { return kind; }

    /** True when the outcome of a create call is unknown and a duplicate-safe reconciliation is required. */
    public boolean cardMayExist() { return kind.cardMayExist; }
}
