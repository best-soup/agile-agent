package com.example.agileagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.agileagent.agent.AgileMasterAgent;
import com.example.agileagent.config.ProjectScope;
import com.example.agileagent.dto.CreateTaskDraftRequest;
import com.example.agileagent.dto.CreateTaskRequest;
import com.example.agileagent.dto.MeetingRecordResponse;
import com.example.agileagent.dto.MeetingTextRequest;
import com.example.agileagent.dto.TaskDraftResponse;
import com.example.agileagent.entity.AuditLogEntity;
import com.example.agileagent.entity.MeetingRecordEntity;
import com.example.agileagent.entity.RagDocumentEntity;
import com.example.agileagent.mapper.AuditLogMapper;
import com.example.agileagent.mapper.MeetingRecordMapper;
import com.example.agileagent.mapper.RagDocumentMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class MeetingRecordService {
    private final MeetingRecordMapper records;
    private final AuditLogMapper audit;
    private final RagDocumentMapper ragDocuments;
    private final ProjectAccessService access;
    private final MeetingSourceImporter importer;
    private final RagService rag;
    private final AgileMasterAgent agent;
    private final TaskDraftService drafts;
    private final TransactionTemplate tx;

    public MeetingRecordService(MeetingRecordMapper records, AuditLogMapper audit, RagDocumentMapper ragDocuments,
            ProjectAccessService access, MeetingSourceImporter importer, RagService rag,
            AgileMasterAgent agent, TaskDraftService drafts, PlatformTransactionManager manager) {
        this.records = records; this.audit = audit; this.ragDocuments = ragDocuments; this.access = access;
        this.importer = importer; this.rag = rag; this.agent = agent; this.drafts = drafts;
        this.tx = new TransactionTemplate(manager);
    }

    public MeetingRecordResponse importText(Long projectId, MeetingTextRequest request) {
        access.requireCurrentProject(projectId, ProjectAccessService.Permission.WRITE);
        if (request == null || request.title() == null || request.title().isBlank())
            throw new MeetingImportException("会议标题不能为空");
        return importRecord(projectId, importer.text(request.title().trim(), request.content(), request.sourceId()),
                request.sourceUrl(), request.meetingDate());
    }

    public MeetingRecordResponse importFile(Long projectId, MultipartFile file) {
        access.requireCurrentProject(projectId, ProjectAccessService.Permission.WRITE);
        return importRecord(projectId, importer.file(file), null, null);
    }

    private MeetingRecordResponse importRecord(Long projectId, MeetingSourceImporter.Imported input,
                                               String sourceUrl, LocalDate meetingDate) {
        String contentHash = UserApiKeyService.sha256(input.content());
        String sourceId = input.sourceId() == null || input.sourceId().isBlank()
                ? "sha256:" + contentHash : input.sourceId().trim();
        String identity = UserApiKeyService.sha256(input.sourceType() + "\n" + sourceId + "\n" + contentHash);
        MeetingRecordEntity existing = findIdentity(projectId, identity);
        if (existing != null) return MeetingRecordResponse.from(existing, true);
        MeetingRecordEntity record = new MeetingRecordEntity();
        LocalDateTime now = LocalDateTime.now();
        record.setProjectId(projectId); record.setSourceType(input.sourceType()); record.setSourceId(sourceId);
        record.setIdentityHash(identity); record.setTitle(input.title()); record.setContent(input.content());
        record.setContentHash(contentHash); record.setSourceUrl(sourceUrl); record.setMeetingDate(meetingDate);
        record.setImportedBy(ProjectScope.requireUserId()); record.setIndexStatus("PENDING");
        record.setCreatedAt(now); record.setUpdatedAt(now);
        try {
            tx.executeWithoutResult(status -> { records.insert(record); audit(projectId, record.getId(), "MEETING_IMPORTED"); });
        } catch (DuplicateKeyException duplicate) {
            MeetingRecordEntity concurrent = findIdentity(projectId, identity);
            if (concurrent != null) return MeetingRecordResponse.from(concurrent, true);
            throw duplicate;
        }
        String ref = reference(record.getId());
        String indexStatus = "READY";
        try {
            rag.indexDocument(projectId, record.getContent(), record.getSourceType(), ref, record.getTitle(),
                    meetingDate == null ? null : meetingDate.toString(), sourceUrl);
            RagDocumentEntity indexed = ragDocuments.selectOne(new LambdaQueryWrapper<RagDocumentEntity>()
                    .eq(RagDocumentEntity::getProjectId, projectId).eq(RagDocumentEntity::getSourceType, record.getSourceType())
                    .eq(RagDocumentEntity::getSourceId, ref).eq(RagDocumentEntity::getStatus, "READY"));
            if (indexed == null) indexStatus = "FAILED";
        } catch (RuntimeException exception) {
            indexStatus = "FAILED";
        }
        String finalStatus = indexStatus;
        tx.executeWithoutResult(status -> {
            record.setIndexStatus(finalStatus);
            record.setIndexFailureReason("FAILED".equals(finalStatus) ? "知识库索引失败" : null);
            record.setUpdatedAt(LocalDateTime.now());
            records.updateById(record);
            audit(projectId, record.getId(), "READY".equals(finalStatus) ? "MEETING_INDEXED" : "MEETING_INDEX_FAILED");
        });
        return MeetingRecordResponse.from(record, true);
    }

    public List<MeetingRecordResponse> list(Long projectId) {
        access.requireCurrentProject(projectId, ProjectAccessService.Permission.READ);
        return records.selectList(new LambdaQueryWrapper<MeetingRecordEntity>()
                .eq(MeetingRecordEntity::getProjectId, projectId).orderByDesc(MeetingRecordEntity::getId))
                .stream().map(r -> MeetingRecordResponse.from(r, false)).toList();
    }

    public MeetingRecordResponse get(Long projectId, Long recordId) {
        access.requireCurrentProject(projectId, ProjectAccessService.Permission.READ);
        return MeetingRecordResponse.from(requireRecord(projectId, recordId), true);
    }

    public TaskDraftResponse createDraft(Long projectId, Long recordId) {
        access.requireCurrentProject(projectId, ProjectAccessService.Permission.WRITE);
        MeetingRecordEntity record = requireRecord(projectId, recordId);
        var extracted = agent.extractIssues(record.getContent());
        var items = extracted == null || extracted.getIssues() == null ? List.<CreateTaskRequest>of()
                : extracted.getIssues().stream().map(issue -> new CreateTaskRequest(issue.getTitle(),
                issue.getAssigneeName(), issue.getDescription(), issue.getPriority(), issue.getDeadline())).toList();
        return tx.execute(status -> {
            TaskDraftResponse result = drafts.createFromMeeting(projectId,
                    new CreateTaskDraftRequest(record.getContent(), reference(recordId)), items, "MEETING_RECORD");
            audit(projectId, recordId, "MEETING_DRAFT_CREATED");
            return result;
        });
    }

    private MeetingRecordEntity requireRecord(Long projectId, Long recordId) {
        MeetingRecordEntity record = records.selectOne(new LambdaQueryWrapper<MeetingRecordEntity>()
                .eq(MeetingRecordEntity::getProjectId, projectId).eq(MeetingRecordEntity::getId, recordId));
        if (record == null) throw new MeetingRecordNotFoundException();
        return record;
    }

    private MeetingRecordEntity findIdentity(Long projectId, String identity) {
        return records.selectOne(new LambdaQueryWrapper<MeetingRecordEntity>()
                .eq(MeetingRecordEntity::getProjectId, projectId).eq(MeetingRecordEntity::getIdentityHash, identity));
    }

    private void audit(Long projectId, Long recordId, String action) {
        audit.insert(AuditLogEntity.builder().projectId(projectId).userId(ProjectScope.requireUserId())
                .action(action).resourceType("MEETING_RECORD").resourceId(recordId)
                .requestId(ProjectScope.currentRequestId()).createdAt(LocalDateTime.now()).build());
    }

    private static String reference(Long id) { return "meeting-record:" + id; }
}
