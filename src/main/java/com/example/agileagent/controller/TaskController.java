package com.example.agileagent.controller;

import com.example.agileagent.agent.AgileMasterAgent;
import com.example.agileagent.agent.ManualReActAgent;
import com.example.agileagent.config.ProjectScope;
import com.example.agileagent.dto.CreateTaskDraftRequest;
import com.example.agileagent.dto.CreateTaskRequest;
import com.example.agileagent.dto.TaskDraftResponse;
import com.example.agileagent.service.RagService;
import com.example.agileagent.service.ProjectAccessService;
import com.example.agileagent.service.TaskDraftService;
import dev.langchain4j.service.TokenStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
@RequestMapping("/api/task")
public class TaskController {

    @Autowired
    private AgileMasterAgent agileMasterAgent;

    @Autowired
    private RagService ragService;

    @Autowired
    private ManualReActAgent manualReActAgent;

    @Autowired
    private ProjectAccessService projectAccessService;

    @Autowired
    private TaskDraftService taskDraftService;

    /**
     * 接收会议纪要并提取为待人工确认的任务草稿
     * POST http://localhost:8080/api/task/process?projectId=1
     */
    @PostMapping("/process")
    public TaskDraftResponse processMeetingText(
            @RequestParam Long projectId,
            @RequestBody String meetingText
    ) {
        requireMeetingText(meetingText);
        System.out.println("收到会议纪要，Agent 开始生成任务草稿...");
        String date = extractDate(meetingText);
        return ProjectScope.runInProject(projectId, () -> {
            projectAccessService.requireCurrentProject(projectId, ProjectAccessService.Permission.WRITE);
            ragService.indexDocument(projectId, meetingText,
                    "会议纪要 " + date, date, "api:/process");
            return createDraft(projectId, meetingText, "api:/process");
        });
    }

    /**
     * 手写 ReAct 入口：索引会议纪要并提取为待人工确认的任务草稿。
     * POST http://localhost:8080/api/task/process/manual?projectId=1
     */
    @PostMapping("/process/manual")
    public TaskDraftResponse processMeetingTextManual(
            @RequestParam Long projectId,
            @RequestBody String meetingText
    ) {
        requireMeetingText(meetingText);
        System.out.println("收到会议纪要，开始生成待确认任务草稿...");
        String date = extractDate(meetingText);
        return ProjectScope.runInProject(projectId, () -> {
            projectAccessService.requireCurrentProject(projectId, ProjectAccessService.Permission.WRITE);
            ragService.indexDocument(projectId, meetingText,
                    "会议纪要 " + date, date, "api:/process/manual");
            return createDraft(projectId, meetingText, "api:/process/manual");
        });
    }

    /**
     * 多轮对话，带 Redis 记忆和 Tool 调用（阻塞式，等完整回复）
     * POST http://localhost:8080/api/task/chat?projectId=1
     */
    @PostMapping("/chat")
    public String chatWithAgent(
            @RequestParam Long projectId,
            @RequestBody String userMessage
    ) {
        System.out.println("收到聊天消息...");
        return ProjectScope.runInProject(projectId, () -> {
            projectAccessService.requireCurrentProject(projectId, ProjectAccessService.Permission.READ);
            return agileMasterAgent.chat(memoryId(projectId), userMessage);
        });
    }

    /**
     * 手写 ReAct 版本：支持 Redis 多轮记忆、工单 Tool 和历史知识检索。
     * POST http://localhost:8080/api/task/chat/manual?projectId=1
     */
    @PostMapping("/chat/manual")
    public ManualReActAgent.ReActResult chatWithManualReAct(
            @RequestParam Long projectId,
            @RequestBody String userMessage
    ) {
        System.out.println("收到聊天消息（手写 ReAct）...");
        return ProjectScope.runInProject(projectId, () -> {
            projectAccessService.requireCurrentProject(projectId, ProjectAccessService.Permission.READ);
            return manualReActAgent.executeChat(projectId, userMessage);
        });
    }

    /**
     * 流式多轮对话（SSE，实时打字效果）
     * POST http://localhost:8080/api/task/chat/stream?projectId=1
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @RequestParam Long projectId,
            @RequestBody String userMessage
    ) {
        System.out.println("收到聊天消息（流式）...");
        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟超时

        TokenStream tokenStream = ProjectScope.runInProject(projectId,
                () -> {
                    projectAccessService.requireCurrentProject(projectId, ProjectAccessService.Permission.READ);
                    return agileMasterAgent.chatStream(memoryId(projectId), userMessage);
                });

        tokenStream.onPartialResponse(token -> {
            try {
                emitter.send(token);
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        });

        tokenStream.onCompleteResponse(response -> emitter.complete());

        tokenStream.onError(emitter::completeWithError);

        Long userId = ProjectScope.requireUserId();
        new Thread(() -> ProjectScope.runAs(userId, projectId, tokenStream::start)).start();

        return emitter;
    }

    private String memoryId(Long projectId) {
        return ProjectScope.requireUserId() + ":" + projectId;
    }

    private TaskDraftResponse createDraft(Long projectId, String meetingText, String sourceRef) {
        var extracted = agileMasterAgent.extractIssues(meetingText);
        var items = extracted == null || extracted.getIssues() == null ? java.util.List.<CreateTaskRequest>of()
                : extracted.getIssues().stream().map(issue -> new CreateTaskRequest(
                        issue.getTitle(), issue.getAssigneeName(), issue.getDescription(),
                        issue.getPriority(), issue.getDeadline())).toList();
        return taskDraftService.createFromMeeting(projectId,
                new CreateTaskDraftRequest(meetingText, sourceRef), items);
    }

    private void requireMeetingText(String meetingText) {
        if (meetingText == null || meetingText.isBlank()) {
            throw new IllegalArgumentException("会议纪要不能为空");
        }
    }

    /** 从文本第一行提取日期，支持 "时间：2026-03-15" "2026年3月15日" 等格式，提取不到则用今天 */
    private String extractDate(String text) {
        if (text == null || text.isBlank()) return java.time.LocalDate.now().toString();
        String firstLine = text.lines().findFirst().orElse("");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})[日]?")
                .matcher(firstLine);
        if (m.find()) {
            return String.format("%s-%02d-%02d", m.group(1),
                    Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        }
        return java.time.LocalDate.now().toString();
    }
}
