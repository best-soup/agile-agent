package com.example.agileagent.controller;

import com.example.agileagent.config.ProjectScope;
import com.example.agileagent.dto.CreateTaskRequest;
import com.example.agileagent.dto.TaskPageResponse;
import com.example.agileagent.dto.UpdateTaskRequest;
import com.example.agileagent.entity.TaskIssueEntity;
import com.example.agileagent.service.TaskIssueService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/tasks")
public class ProjectTaskController {
    private final TaskIssueService tasks;

    public ProjectTaskController(TaskIssueService tasks) {
        this.tasks = tasks;
    }

    @GetMapping
    public TaskPageResponse list(@PathVariable Long projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String assigneeName,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) Boolean completed) {
        return ProjectScope.runInProject(projectId,
                () -> tasks.listTasks(projectId, page, size, assigneeName, priority, completed));
    }

    @GetMapping("/{taskId}")
    public TaskIssueEntity get(@PathVariable Long projectId, @PathVariable Long taskId) {
        return ProjectScope.runInProject(projectId, () -> tasks.getTask(projectId, taskId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskIssueEntity create(@PathVariable Long projectId, @RequestBody CreateTaskRequest request) {
        return ProjectScope.runInProject(projectId, () -> tasks.createTask(projectId, request));
    }

    @PutMapping("/{taskId}")
    public TaskIssueEntity update(@PathVariable Long projectId, @PathVariable Long taskId,
                                  @RequestBody UpdateTaskRequest request) {
        return ProjectScope.runInProject(projectId, () -> tasks.updateTask(projectId, taskId, request));
    }

    @PatchMapping("/{taskId}/completion")
    public TaskIssueEntity completion(@PathVariable Long projectId, @PathVariable Long taskId,
                                      @RequestBody CompletionRequest request) {
        if (request.completed() == null) throw new IllegalArgumentException("completed 不能为空");
        return ProjectScope.runInProject(projectId, () -> tasks.setCompleted(projectId, taskId, request.completed()));
    }

    @DeleteMapping("/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long projectId, @PathVariable Long taskId) {
        ProjectScope.runInProject(projectId, () -> tasks.deleteTask(projectId, taskId));
    }

    public record CompletionRequest(Boolean completed) {}
}
