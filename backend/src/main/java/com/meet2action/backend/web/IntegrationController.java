package com.meet2action.backend.web;

import com.meet2action.backend.service.TrelloSyncService;
import com.meet2action.backend.web.Dtos.TrelloStatusResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations")
public class IntegrationController {

    private final TrelloSyncService syncService;

    public IntegrationController(TrelloSyncService syncService) {
        this.syncService = syncService;
    }

    @GetMapping("/trello")
    public TrelloStatusResponse trello() {
        return new TrelloStatusResponse(syncService.isConfigured(), syncService.isWorkListsConfigured());
    }
}
