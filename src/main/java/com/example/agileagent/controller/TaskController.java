package com.example.agileagent.controller;

import com.example.agileagent.agent.AgileMasterAgent;
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
        ragService.indexDocument(projectId, meetingText); // 同时索引到向量库
        return agileMasterAgent.processAndSave(meetingText, projectId);
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
}
