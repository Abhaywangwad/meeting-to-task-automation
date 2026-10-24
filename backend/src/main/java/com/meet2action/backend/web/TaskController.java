package com.meet2action.backend.web;

import com.meet2action.backend.domain.ReviewStatus;
import com.meet2action.backend.service.TaskService;
import com.meet2action.backend.service.TrelloSyncService;
import com.meet2action.backend.web.Dtos.SyncRequest;
import com.meet2action.backend.web.Dtos.ApproveRequest;
import com.meet2action.backend.web.Dtos.RejectRequest;
import com.meet2action.backend.web.Dtos.TaskResponse;
import com.meet2action.backend.web.Dtos.UpdateTaskRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;
    private final TrelloSyncService syncService;

    public TaskController(TaskService taskService, TrelloSyncService syncService) {
        this.taskService = taskService;
        this.syncService = syncService;
    }

    @GetMapping
    public List<TaskResponse> list(@RequestParam(name = "meeting_id", required = false) String meetingId,
                                   @RequestParam(name = "review_status", required = false) ReviewStatus reviewStatus) {
        return taskService.list(meetingId, reviewStatus);
    }

    @GetMapping("/{taskId}")
    public TaskResponse get(@PathVariable String taskId) {
        return taskService.get(taskId);
    }

    @PatchMapping("/{taskId}")
    public TaskResponse edit(@PathVariable String taskId, @Valid @RequestBody UpdateTaskRequest request) {
        return taskService.edit(taskId, request);
    }

    @PostMapping("/{taskId}/approve")
    public TaskResponse approve(@PathVariable String taskId, @Valid @RequestBody ApproveRequest request) {
        return taskService.approve(taskId, request.version());
    }

    @PostMapping("/{taskId}/reject")
    public TaskResponse reject(@PathVariable String taskId,
                               @RequestBody(required = false) RejectRequest request) {
        return taskService.reject(taskId, request == null ? null : request.version());
    }

    /** Explicit user action only. Never called by approval, analysis or startup. */
    @PostMapping("/{taskId}/sync")
    public TaskResponse sync(@PathVariable String taskId, @Valid @RequestBody SyncRequest request) {
        return syncService.sync(taskId, request.version());
    }

    /** Looks for an already-created card after an uncertain sync; creates nothing. */
    @PostMapping("/{taskId}/sync/reconcile")
    public TaskResponse reconcile(@PathVariable String taskId) {
        return syncService.reconcile(taskId);
    }
}
