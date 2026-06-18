package com.example.agileagent.controller;

import com.example.agileagent.agent.AgileMasterAgent;
import com.example.agileagent.agent.ManualReActAgent;
import com.example.agileagent.service.RagService;
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

    /**
     * 接收会议纪要，Agent 自动提取任务并通过 Tool 逐条入库
     * POST http://localhost:8080/api/task/process?projectId=1
     */
    @PostMapping("/process")
    public String processMeetingText(
            @RequestParam Long projectId,
            @RequestBody String meetingText
    ) {
        System.out.println("收到会议纪要，Agent 开始提取并入库...");
        String date = extractDate(meetingText);
        ragService.indexDocument(projectId, meetingText,
                "会议纪要 " + date, date, "api:/process");
        return agileMasterAgent.processAndSave(meetingText, projectId);
    }

    /**
     * 手写 ReAct 版本：索引会议纪要并逐条创建工单。
     * POST http://localhost:8080/api/task/process/manual?projectId=1
     */
    @PostMapping("/process/manual")
    public ManualReActAgent.ReActResult processMeetingTextManual(
            @RequestParam Long projectId,
            @RequestBody String meetingText
    ) {
        System.out.println("收到会议纪要，手写 ReAct 开始提取并入库...");
        String date = extractDate(meetingText);
        ragService.indexDocument(projectId, meetingText,
                "会议纪要 " + date, date, "api:/process/manual");
        return manualReActAgent.processMeeting(projectId, meetingText);
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
        return agileMasterAgent.chat(projectId, userMessage);
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
        return manualReActAgent.executeChat(projectId, userMessage);
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

        TokenStream tokenStream = agileMasterAgent.chatStream(projectId, userMessage);

        tokenStream.onPartialResponse(token -> {
            try {
                emitter.send(token);
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        });

        tokenStream.onCompleteResponse(response -> emitter.complete());

        tokenStream.onError(emitter::completeWithError);

        new Thread(tokenStream::start).start();

        return emitter;
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
