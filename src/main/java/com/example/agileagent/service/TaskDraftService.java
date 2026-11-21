package com.example.agileagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.agileagent.config.ProjectScope;
import com.example.agileagent.dto.CreateTaskDraftRequest;
import com.example.agileagent.dto.CreateTaskRequest;
import com.example.agileagent.dto.SubmitTaskDraftRequest;
import com.example.agileagent.dto.TaskDraftResponse;
import com.example.agileagent.dto.UpdateTaskDraftItemRequest;
import com.example.agileagent.entity.AuditLogEntity;
import com.example.agileagent.entity.TaskDraftEntity;
import com.example.agileagent.entity.TaskDraftItemEntity;
import com.example.agileagent.mapper.AuditLogMapper;
import com.example.agileagent.mapper.TaskDraftItemMapper;
import com.example.agileagent.mapper.TaskDraftMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TaskDraftService {
    public static final String DRAFT = "DRAFT";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String SUBMITTING = "SUBMITTING";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    private static final Set<String> PRIORITIES = Set.of("HIGH", "MEDIUM", "LOW");
    private final TaskDraftMapper draftMapper;
    private final TaskDraftItemMapper itemMapper;
    private final AuditLogMapper auditMapper;
    private final TaskIssueService taskIssueService;
    private final ProjectAccessService access;
    private final TransactionTemplate transactionTemplate;

    public TaskDraftService(TaskDraftMapper draftMapper, TaskDraftItemMapper itemMapper,
                            AuditLogMapper auditMapper, TaskIssueService taskIssueService,
                            ProjectAccessService access, PlatformTransactionManager transactionManager) {
        this.draftMapper = draftMapper;
        this.itemMapper = itemMapper;
        this.auditMapper = auditMapper;
        this.taskIssueService = taskIssueService;
        this.access = access;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public TaskDraftResponse createFromMeeting(Long projectId, CreateTaskDraftRequest request,
                                               List<CreateTaskRequest> items) {
        access.requireCurrentProject(projectId, ProjectAccessService.Permission.WRITE);
        if (request == null || request.meetingText() == null || request.meetingText().isBlank())
            throw new IllegalArgumentException("会议纪要不能为空");
        List<CreateTaskRequest> normalized = items == null ? List.of() : items.stream().map(this::validate).toList();
        if (normalized.isEmpty()) throw new IllegalArgumentException("AI 没有提取到可执行任务");
        Long userId = ProjectScope.requireUserId();
        LocalDateTime now = LocalDateTime.now();
        TaskDraftEntity draft = TaskDraftEntity.builder().projectId(projectId).createdBy(userId)
                .sourceType("MEETING").sourceRef(request.sourceRef())
                .sourceContentHash(sha256(request.meetingText())).status(DRAFT)
                .createdAt(now).updatedAt(now).build();
        return transactionTemplate.execute(status -> {
            draftMapper.insert(draft);
            int order = 0;
            for (CreateTaskRequest item : normalized) {
                itemMapper.insert(toItem(draft.getId(), item, order++));
            }
            audit(projectId, userId, "DRAFT_CREATED", "TASK_DRAFT", draft.getId(), requestId(),
                    "meeting items=" + normalized.size());
            return get(projectId, draft.getId());
        });
    }

    public TaskDraftResponse get(Long projectId, Long draftId) {
        access.requireCurrentProject(projectId, ProjectAccessService.Permission.READ);
        TaskDraftEntity draft = requireDraft(projectId, draftId);
        List<TaskDraftItemEntity> items = itemMapper.selectList(new LambdaQueryWrapper<TaskDraftItemEntity>()
                .eq(TaskDraftItemEntity::getDraftId, draftId)
                .orderByAsc(TaskDraftItemEntity::getSortOrder).orderByAsc(TaskDraftItemEntity::getId));
        return new TaskDraftResponse(draft, items);
    }

    @Transactional
    public TaskDraftItemEntity updateItem(Long projectId, Long draftId, Long itemId,
                                          UpdateTaskDraftItemRequest request) {
        TaskDraftEntity draft = writableDraft(projectId, draftId);
        requireDraftItem(draft.getId(), itemId);
        if (!DRAFT.equals(draft.getStatus())) throw new DraftStateException("只有 DRAFT 状态可以编辑草稿项");
        CreateTaskRequest normalized = validate(new CreateTaskRequest(request.title(), request.assigneeName(),
                request.description(), request.priority(), request.deadline()));
        TaskDraftItemEntity item = TaskDraftItemEntity.builder().id(itemId).draftId(draftId)
                .title(normalized.title()).assigneeName(normalized.assigneeName()).description(normalized.description())
                .priority(normalized.priority()).deadline(parseDate(normalized.deadline()))
                .sortOrder(request.sortOrder() == null ? 0 : request.sortOrder()).updatedAt(LocalDateTime.now()).build();
        itemMapper.updateById(item);
        touch(draftId);
        audit(projectId, ProjectScope.requireUserId(), "DRAFT_ITEM_UPDATED", "TASK_DRAFT_ITEM", itemId, requestId(), null);
        return item;
    }

    @Transactional
    public void deleteItem(Long projectId, Long draftId, Long itemId) {
        TaskDraftEntity draft = writableDraft(projectId, draftId);
        requireDraftItem(draft.getId(), itemId);
        if (!DRAFT.equals(draft.getStatus())) throw new DraftStateException("只有 DRAFT 状态可以删除草稿项");
        itemMapper.delete(new LambdaQueryWrapper<TaskDraftItemEntity>().eq(TaskDraftItemEntity::getId, itemId)
                .eq(TaskDraftItemEntity::getDraftId, draftId));
        touch(draftId);
        audit(projectId, ProjectScope.requireUserId(), "DRAFT_ITEM_DELETED", "TASK_DRAFT_ITEM", itemId, requestId(), null);
    }

    @Transactional
    public TaskDraftResponse confirm(Long projectId, Long draftId) {
        TaskDraftEntity draft = writableDraft(projectId, draftId);
        if (!DRAFT.equals(draft.getStatus())) throw new DraftStateException("只有 DRAFT 状态可以确认");
        if (itemMapper.selectCount(new LambdaQueryWrapper<TaskDraftItemEntity>().eq(TaskDraftItemEntity::getDraftId, draftId)) == 0)
            throw new IllegalArgumentException("草稿至少需要一个任务项");
        LocalDateTime now = LocalDateTime.now();
        draftMapper.update(null, new LambdaUpdateWrapper<TaskDraftEntity>().eq(TaskDraftEntity::getId, draftId)
                .set(TaskDraftEntity::getStatus, CONFIRMED).set(TaskDraftEntity::getConfirmedAt, now)
                .set(TaskDraftEntity::getUpdatedAt, now));
        audit(projectId, ProjectScope.requireUserId(), "DRAFT_CONFIRMED", "TASK_DRAFT", draftId, requestId(), null);
        return get(projectId, draftId);
    }

    @Transactional
    public TaskDraftResponse cancel(Long projectId, Long draftId) {
        TaskDraftEntity draft = writableDraft(projectId, draftId);
        if (!DRAFT.equals(draft.getStatus())) throw new DraftStateException("只有 DRAFT 状态可以取消");
        draftMapper.update(null, new LambdaUpdateWrapper<TaskDraftEntity>().eq(TaskDraftEntity::getId, draftId)
                .set(TaskDraftEntity::getStatus, CANCELLED).set(TaskDraftEntity::getUpdatedAt, LocalDateTime.now()));
        audit(projectId, ProjectScope.requireUserId(), "DRAFT_CANCELLED", "TASK_DRAFT", draftId, requestId(), null);
        return get(projectId, draftId);
    }

    public TaskDraftResponse submit(Long projectId, Long draftId, SubmitTaskDraftRequest request) {
        TaskDraftEntity existing = writableDraft(projectId, draftId);
        String key = request == null ? null : request.idempotencyKey();
        if (key == null || key.isBlank() || key.length() > 100) throw new IllegalArgumentException("idempotencyKey 不能为空且不能超过 100 个字符");
        if (SUBMITTED.equals(existing.getStatus()) && key.equals(existing.getIdempotencyKey())) return get(projectId, draftId);
        if (existing.getIdempotencyKey() != null && !key.equals(existing.getIdempotencyKey())) throw new DraftStateException("草稿已使用其他提交键");
        Boolean claimed = transactionTemplate.execute(status -> {
            LambdaUpdateWrapper<TaskDraftEntity> claim = new LambdaUpdateWrapper<TaskDraftEntity>()
                    .eq(TaskDraftEntity::getId, draftId).eq(TaskDraftEntity::getProjectId, projectId)
                    .in(TaskDraftEntity::getStatus, CONFIRMED, FAILED)
                    .and(q -> q.isNull(TaskDraftEntity::getIdempotencyKey)
                            .or().eq(TaskDraftEntity::getIdempotencyKey, key))
                    .set(TaskDraftEntity::getStatus, SUBMITTING)
                    .set(TaskDraftEntity::getIdempotencyKey, key)
                    .set(TaskDraftEntity::getFailureReason, null)
                    .set(TaskDraftEntity::getUpdatedAt, LocalDateTime.now());
            if (draftMapper.update(null, claim) != 1) return false;
            audit(projectId, ProjectScope.requireUserId(), "DRAFT_SUBMITTING", "TASK_DRAFT", draftId, requestId(), "idempotencyKey=" + key);
            return true;
        });
        if (!Boolean.TRUE.equals(claimed)) {
            TaskDraftEntity current = requireDraft(projectId, draftId);
            if (SUBMITTED.equals(current.getStatus()) && key.equals(current.getIdempotencyKey())) return get(projectId, draftId);
            if (SUBMITTING.equals(current.getStatus())) throw new DraftStateException("草稿正在提交");
            if (CANCELLED.equals(current.getStatus())) throw new DraftStateException("已取消草稿不能提交");
            throw new DraftStateException("草稿必须先人工确认后才能提交");
        }
        try {
            return transactionTemplate.execute(status -> {
                TaskDraftEntity draft = requireDraft(projectId, draftId);
                List<TaskDraftItemEntity> items = itemMapper.selectList(new LambdaQueryWrapper<TaskDraftItemEntity>()
                        .eq(TaskDraftItemEntity::getDraftId, draftId).orderByAsc(TaskDraftItemEntity::getSortOrder));
                List<CreateTaskRequest> requests = items.stream().map(item -> new CreateTaskRequest(item.getTitle(), item.getAssigneeName(), item.getDescription(), item.getPriority(), item.getDeadline() == null ? null : item.getDeadline().toString())).toList();
                List<Long> ids = taskIssueService.createTasksFromDraft(projectId, requests);
                LocalDateTime now = LocalDateTime.now();
                int completed = draftMapper.update(null, new LambdaUpdateWrapper<TaskDraftEntity>()
                        .eq(TaskDraftEntity::getId, draftId).eq(TaskDraftEntity::getProjectId, projectId)
                        .eq(TaskDraftEntity::getStatus, SUBMITTING).eq(TaskDraftEntity::getIdempotencyKey, key)
                        .set(TaskDraftEntity::getStatus, SUBMITTED).set(TaskDraftEntity::getSubmittedTaskIds, ids.stream().map(String::valueOf).collect(Collectors.joining(",")))
                        .set(TaskDraftEntity::getSubmittedAt, now).set(TaskDraftEntity::getUpdatedAt, now));
                if (completed != 1) throw new DraftStateException("草稿提交状态已被其他请求改变");
                audit(projectId, ProjectScope.requireUserId(), "DRAFT_SUBMITTED", "TASK_DRAFT", draftId, requestId(), "idempotencyKey=" + key + ";taskIds=" + ids);
                return get(projectId, draftId);
            });
        } catch (RuntimeException exception) {
            transactionTemplate.executeWithoutResult(status -> {
                draftMapper.update(null, new LambdaUpdateWrapper<TaskDraftEntity>().eq(TaskDraftEntity::getId, draftId)
                        .eq(TaskDraftEntity::getProjectId, projectId).eq(TaskDraftEntity::getStatus, SUBMITTING)
                        .eq(TaskDraftEntity::getIdempotencyKey, key)
                        .set(TaskDraftEntity::getStatus, FAILED)
                        .set(TaskDraftEntity::getFailureReason, "任务批量提交失败，请检查任务数据后重试")
                        .set(TaskDraftEntity::getUpdatedAt, LocalDateTime.now()));
                audit(projectId, ProjectScope.requireUserId(), "DRAFT_SUBMIT_FAILED", "TASK_DRAFT", draftId, requestId(), "idempotencyKey=" + key + ";failure=批量提交失败");
            });
            throw exception;
        }
    }

    private TaskDraftEntity writableDraft(Long projectId, Long draftId) {
        access.requireCurrentProject(projectId, ProjectAccessService.Permission.WRITE);
        TaskDraftEntity draft = requireDraft(projectId, draftId);
        Long userId = ProjectScope.requireUserId();
        String role = access.requireAccess(userId, projectId, ProjectAccessService.Permission.WRITE).getRole();
        if (!userId.equals(draft.getCreatedBy()) && !Set.of("OWNER", "ADMIN").contains(role))
            throw new SecurityException("只有草稿创建者或项目管理员可以修改草稿");
        return draft;
    }

    private TaskDraftEntity requireDraft(Long projectId, Long draftId) {
        TaskDraftEntity draft = draftMapper.selectOne(new LambdaQueryWrapper<TaskDraftEntity>()
                .eq(TaskDraftEntity::getId, draftId).eq(TaskDraftEntity::getProjectId, projectId));
        if (draft == null) throw new DraftNotFoundException();
        return draft;
    }

    private TaskDraftItemEntity requireDraftItem(Long draftId, Long itemId) {
        TaskDraftItemEntity item = itemMapper.selectOne(new LambdaQueryWrapper<TaskDraftItemEntity>()
                .eq(TaskDraftItemEntity::getId, itemId).eq(TaskDraftItemEntity::getDraftId, draftId));
        if (item == null) throw new DraftNotFoundException();
        return item;
    }

    private CreateTaskRequest validate(CreateTaskRequest request) {
        if (request == null || request.title() == null || request.title().isBlank() || request.title().trim().length() > 255)
            throw new IllegalArgumentException("草稿标题不能为空且不能超过 255 个字符");
        String priority = request.priority() == null ? "MEDIUM" : request.priority().trim().toUpperCase(Locale.ROOT);
        if (!PRIORITIES.contains(priority)) throw new IllegalArgumentException("优先级只能是 HIGH、MEDIUM 或 LOW");
        if (request.assigneeName() != null && request.assigneeName().length() > 50)
            throw new IllegalArgumentException("负责人姓名不能超过 50 个字符");
        parseDate(request.deadline());
        return new CreateTaskRequest(request.title().trim(), request.assigneeName(), request.description(), priority, request.deadline());
    }

    private TaskDraftItemEntity toItem(Long draftId, CreateTaskRequest request, int order) {
        return TaskDraftItemEntity.builder().draftId(draftId).title(request.title()).assigneeName(request.assigneeName())
                .description(request.description()).priority(request.priority()).deadline(parseDate(request.deadline()))
                .sortOrder(order).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private LocalDate parseDate(String value) {
        if (value == null) return null;
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException exception) { throw new IllegalArgumentException("截止日期必须是有效的 YYYY-MM-DD 日期", exception); }
    }

    private void touch(Long draftId) {
        draftMapper.update(null, new LambdaUpdateWrapper<TaskDraftEntity>().eq(TaskDraftEntity::getId, draftId)
                .set(TaskDraftEntity::getUpdatedAt, LocalDateTime.now()));
    }

    private void audit(Long projectId, Long userId, String action, String resourceType, Long resourceId, String requestId, String detail) {
        auditMapper.insert(AuditLogEntity.builder().projectId(projectId).userId(userId).action(action)
                .resourceType(resourceType).resourceId(resourceId).requestId(requestId).detail(detail).createdAt(LocalDateTime.now()).build());
    }

    private String requestId() {
        return ProjectScope.currentRequestId();
    }

    private String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
}
