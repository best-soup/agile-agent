package com.example.agileagent.controller;

import com.example.agileagent.config.ProjectScope;
import com.example.agileagent.dto.MeetingRecordResponse;
import com.example.agileagent.dto.MeetingTextRequest;
import com.example.agileagent.dto.TaskDraftResponse;
import com.example.agileagent.service.MeetingRecordService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}/meeting-records")
public class MeetingRecordController {
    private final MeetingRecordService service;
    public MeetingRecordController(MeetingRecordService service) { this.service = service; }

    @PostMapping("/text")
    public MeetingRecordResponse text(@PathVariable Long projectId, @Valid @RequestBody MeetingTextRequest request) {
        return ProjectScope.runInProject(projectId, () -> service.importText(projectId, request));
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MeetingRecordResponse file(@PathVariable Long projectId, @RequestPart("file") MultipartFile file) {
        return ProjectScope.runInProject(projectId, () -> service.importFile(projectId, file));
    }

    @GetMapping
    public List<MeetingRecordResponse> list(@PathVariable Long projectId) {
        return ProjectScope.runInProject(projectId, () -> service.list(projectId));
    }

    @GetMapping("/{recordId}")
    public MeetingRecordResponse get(@PathVariable Long projectId, @PathVariable Long recordId) {
        return ProjectScope.runInProject(projectId, () -> service.get(projectId, recordId));
    }

    @PostMapping("/{recordId}/task-drafts")
    public TaskDraftResponse draft(@PathVariable Long projectId, @PathVariable Long recordId) {
        return ProjectScope.runInProject(projectId, () -> service.createDraft(projectId, recordId));
    }
}
