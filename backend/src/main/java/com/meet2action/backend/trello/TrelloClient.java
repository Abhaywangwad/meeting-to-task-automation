package com.meet2action.backend.trello;

import java.util.Optional;

public interface TrelloClient {

    record Card(String id, String url) {}

    /**
     * Creates exactly one card (a single HTTP request, never retried here).
     *
     * @param dueIso ISO-8601 instant, or null for no due date
     * @throws TrelloException on any failure; see {@link TrelloException#cardMayExist()}
     */
    Card createCard(String listId, String name, String description, String dueIso);

    /**
     * Moves an EXISTING card to another list (PUT /cards/{id}). It never creates a card, and repeating it is
     * harmless: setting the same list again changes nothing.
     *
     * @throws TrelloException on any failure
     */
    void moveCard(String cardId, String listId);

    /** Looks through the open cards of the list for one whose description contains the exact marker. */
    Optional<Card> findCardByMarker(String listId, String marker);
}
