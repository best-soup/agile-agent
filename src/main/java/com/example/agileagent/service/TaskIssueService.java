package com.example.agileagent.service;

import com.example.agileagent.dto.CreateTaskRequest;
import com.example.agileagent.dto.TaskIssueDTO;
import com.example.agileagent.dto.TaskPageResponse;
import com.example.agileagent.dto.UpdateTaskRequest;
import com.example.agileagent.entity.TaskIssueEntity;

import java.util.List;

public interface TaskIssueService {
    TaskPageResponse listTasks(Long projectId, int page, int size, String assigneeName, String priority, Boolean completed);
    List<TaskIssueEntity> listAllTasks(Long projectId, String assigneeName);
    TaskIssueEntity getTask(Long projectId, Long taskId);
    TaskIssueEntity createTask(Long projectId, CreateTaskRequest request);
    TaskIssueEntity updateTask(Long projectId, Long taskId, UpdateTaskRequest request);
    TaskIssueEntity updatePriority(Long projectId, Long taskId, String priority);
    TaskIssueEntity setCompleted(Long projectId, Long taskId, boolean completed);
    TaskIssueEntity deleteTask(Long projectId, Long taskId);
    void saveIssuesFromAi(Long projectId, List<TaskIssueDTO> aiExtractedIssues);
    List<Long> createTasksFromDraft(Long projectId, List<CreateTaskRequest> requests);
}
