package com.example.agileagent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.agileagent.dto.CreateTaskRequest;
import com.example.agileagent.dto.TaskIssueDTO;
import com.example.agileagent.dto.TaskPageResponse;
import com.example.agileagent.dto.UpdateTaskRequest;
import com.example.agileagent.entity.TaskIssueEntity;
import com.example.agileagent.mapper.TaskIssueMapper;
import com.example.agileagent.service.ProjectAccessService;
import com.example.agileagent.service.TaskIssueService;
import com.example.agileagent.service.TaskNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class TaskIssueServiceImpl implements TaskIssueService {
    private static final Set<String> PRIORITIES = Set.of("HIGH", "MEDIUM", "LOW");
    private final TaskIssueMapper mapper;
    private final ProjectAccessService access;

    public TaskIssueServiceImpl(TaskIssueMapper mapper, ProjectAccessService access) {
        this.mapper = mapper;
        this.access = access;
    }

    @Override
    public TaskPageResponse listTasks(Long projectId, int page, int size, String assigneeName,
                                      String priority, Boolean completed) {
        access.requireCurrentProject(projectId, ProjectAccessService.Permission.READ);
        if (page < 1 || page > 1000000 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page 必须在 1 到 1000000 之间，size 必须在 1 到 100 之间");
        }
        String normalized = priority == null ? null : priority(priority, false);
        LambdaQueryWrapper<TaskIssueEntity> query = query(projectId, assigneeName)
                .eq(normalized != null, TaskIssueEntity::getPriority, normalized)
                .eq(completed != null, TaskIssueEntity::getIsCompleted, Boolean.TRUE.equals(completed) ? 1 : 0)
                .orderByDesc(TaskIssueEntity::getId);
        Page<TaskIssueEntity> result = mapper.selectPage(new Page<>(page, size), query);
        return new TaskPageResponse(page, size, result.getTotal(), result.getRecords());
    }

    @Override
    public List<TaskIssueEntity> listAllTasks(Long projectId, String assigneeName) {
        access.requireCurrentProject(projectId, ProjectAccessService.Permission.READ);
        return mapper.selectList(query(projectId, assigneeName).orderByAsc(TaskIssueEntity::getId));
    }

    @Override
    public TaskIssueEntity getTask(Long projectId, Long taskId) {
        access.requireCurrentProject(projectId, ProjectAccessService.Permission.READ);
        return requireTask(projectId, taskId);
    }

    @Override
    @Transactional
    public TaskIssueEntity createTask(Long projectId, CreateTaskRequest request) {
        access.requireCurrentProject(projectId, ProjectAccessService.Permission.WRITE);
        TaskIssueEntity task = newTask(projectId, request);
        mapper.insert(task);
        return task;
    }

    @Override
    @Transactional
    public TaskIssueEntity updateTask(Long projectId, Long taskId, UpdateTaskRequest request) {
        access.requireCurrentProject(projectId, ProjectAccessService.Permission.WRITE);
        TaskIssueEntity task = requireTask(projectId, taskId);
        if (request == null) throw new IllegalArgumentException("请求不能为空");
        String title = title(request.title());
        String priority = priority(request.priority(), false);
        LocalDate deadline = deadline(request.deadline());
        validateAssignee(request.assigneeName());
        int affected = mapper.update(null, new LambdaUpdateWrapper<TaskIssueEntity>()
                .eq(TaskIssueEntity::getId, taskId).eq(TaskIssueEntity::getProjectId, projectId)
                .set(TaskIssueEntity::getTitle, title)
                .set(TaskIssueEntity::getAssigneeName, request.assigneeName())
                .set(TaskIssueEntity::getDescription, request.description())
                .set(TaskIssueEntity::getPriority, priority)
                .set(TaskIssueEntity::getDeadline, deadline));
        if (affected == 0) throw new TaskNotFoundException();
        task.setTitle(title);
        task.setAssigneeName(request.assigneeName());
        task.setDescription(request.description());
        task.setPriority(priority);
        task.setDeadline(deadline);
        return task;
    }

    @Override
    @Transactional
    public TaskIssueEntity updatePriority(Long projectId, Long taskId, String priority) {
        access.requireCurrentProject(projectId, ProjectAccessService.Permission.WRITE);
        TaskIssueEntity task = requireTask(projectId, taskId);
        String value = priority(priority, false);
        int affected = mapper.update(null, new LambdaUpdateWrapper<TaskIssueEntity>()
                .eq(TaskIssueEntity::getId, taskId).eq(TaskIssueEntity::getProjectId, projectId)
                .set(TaskIssueEntity::getPriority, value));
        if (affected == 0) throw new TaskNotFoundException();
        task.setPriority(value);
        return task;
    }

    @Override
    @Transactional
    public TaskIssueEntity setCompleted(Long projectId, Long taskId, boolean completed) {
        access.requireCurrentProject(projectId, ProjectAccessService.Permission.WRITE);
        TaskIssueEntity task = requireTask(projectId, taskId);
        int affected = mapper.update(null, new LambdaUpdateWrapper<TaskIssueEntity>()
                .eq(TaskIssueEntity::getId, taskId).eq(TaskIssueEntity::getProjectId, projectId)
                .set(TaskIssueEntity::getIsCompleted, completed ? 1 : 0));
        if (affected == 0) throw new TaskNotFoundException();
        task.setIsCompleted(completed ? 1 : 0);
        return task;
    }

    @Override
    @Transactional
    public TaskIssueEntity deleteTask(Long projectId, Long taskId) {
        access.requireCurrentProject(projectId, ProjectAccessService.Permission.WRITE);
        TaskIssueEntity task = requireTask(projectId, taskId);
        int affected = mapper.delete(new LambdaQueryWrapper<TaskIssueEntity>()
                .eq(TaskIssueEntity::getProjectId, projectId).eq(TaskIssueEntity::getId, taskId));
        if (affected == 0) throw new TaskNotFoundException();
        return task;
    }

    @Override
    @Transactional
    public void saveIssuesFromAi(Long projectId, List<TaskIssueDTO> issues) {
        access.requireCurrentProject(projectId, ProjectAccessService.Permission.WRITE);
        if (issues == null || issues.isEmpty()) return;
        List<TaskIssueEntity> tasks = issues.stream().map(issue -> {
            if (issue == null) throw new IllegalArgumentException("工单不能为空");
            return newTask(projectId, new CreateTaskRequest(issue.getTitle(), issue.getAssigneeName(),
                    issue.getDescription(), issue.getPriority(), issue.getDeadline()));
        }).toList();
        tasks.forEach(mapper::insert);
    }

    private TaskIssueEntity newTask(Long projectId, CreateTaskRequest request) {
        if (request == null) throw new IllegalArgumentException("请求不能为空");
        validateAssignee(request.assigneeName());
        return TaskIssueEntity.builder().projectId(projectId).title(title(request.title()))
                .assigneeName(request.assigneeName()).description(request.description())
                .priority(priority(request.priority(), true)).deadline(deadline(request.deadline()))
                .isCompleted(0).build();
    }

    private LambdaQueryWrapper<TaskIssueEntity> query(Long projectId, String assigneeName) {
        return new LambdaQueryWrapper<TaskIssueEntity>().eq(TaskIssueEntity::getProjectId, projectId)
                .eq(assigneeName != null && !assigneeName.isBlank(), TaskIssueEntity::getAssigneeName, assigneeName);
    }

    private TaskIssueEntity requireTask(Long projectId, Long taskId) {
        TaskIssueEntity task = mapper.selectOne(new LambdaQueryWrapper<TaskIssueEntity>()
                .eq(TaskIssueEntity::getProjectId, projectId).eq(TaskIssueEntity::getId, taskId));
        if (task == null) throw new TaskNotFoundException();
        return task;
    }

    private String title(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 255)
            throw new IllegalArgumentException("标题不能为空且不能超过 255 个字符");
        return value.trim();
    }

    private void validateAssignee(String value) {
        if (value != null && value.length() > 50)
            throw new IllegalArgumentException("负责人姓名不能超过 50 个字符");
    }

    private String priority(String value, boolean defaultAllowed) {
        if (value == null && defaultAllowed) return "MEDIUM";
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!PRIORITIES.contains(normalized)) throw new IllegalArgumentException("优先级只能是 HIGH、MEDIUM 或 LOW");
        return normalized;
    }

    private LocalDate deadline(String value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("截止日期必须是有效的 YYYY-MM-DD 日期", exception);
        }
    }
}
