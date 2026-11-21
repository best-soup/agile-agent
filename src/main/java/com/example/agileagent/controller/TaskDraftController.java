package com.example.agileagent.controller;

import com.example.agileagent.config.ProjectScope;
import com.example.agileagent.dto.SubmitTaskDraftRequest;
import com.example.agileagent.dto.TaskDraftResponse;
import com.example.agileagent.dto.UpdateTaskDraftItemRequest;
import com.example.agileagent.entity.TaskDraftItemEntity;
import com.example.agileagent.service.TaskDraftService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/projects/{projectId}/task-drafts")
public class TaskDraftController {
    private final TaskDraftService drafts;

    public TaskDraftController(TaskDraftService drafts) { this.drafts = drafts; }

    @GetMapping("/{draftId}")
    public TaskDraftResponse get(@PathVariable Long projectId, @PathVariable Long draftId) {
        return ProjectScope.runInProject(projectId, () -> drafts.get(projectId, draftId));
    }

    @PatchMapping("/{draftId}/items/{itemId}")
    public TaskDraftItemEntity updateItem(@PathVariable Long projectId, @PathVariable Long draftId,
                                          @PathVariable Long itemId,
                                          @Valid @RequestBody UpdateTaskDraftItemRequest request) {
        return ProjectScope.runInProject(projectId, () -> drafts.updateItem(projectId, draftId, itemId, request));
    }

    @DeleteMapping("/{draftId}/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItem(@PathVariable Long projectId, @PathVariable Long draftId,
                           @PathVariable Long itemId) {
        ProjectScope.runInProject(projectId, () -> drafts.deleteItem(projectId, draftId, itemId));
    }

    @PostMapping("/{draftId}/confirm")
    public TaskDraftResponse confirm(@PathVariable Long projectId, @PathVariable Long draftId) {
        return ProjectScope.runInProject(projectId, () -> drafts.confirm(projectId, draftId));
    }

    @PostMapping("/{draftId}/cancel")
    public TaskDraftResponse cancel(@PathVariable Long projectId, @PathVariable Long draftId) {
        return ProjectScope.runInProject(projectId, () -> drafts.cancel(projectId, draftId));
    }

    @PostMapping("/{draftId}/submit")
    public TaskDraftResponse submit(@PathVariable Long projectId, @PathVariable Long draftId,
                                    @Valid @RequestBody SubmitTaskDraftRequest request) {
        return ProjectScope.runInProject(projectId, () -> drafts.submit(projectId, draftId, request));
    }
}
