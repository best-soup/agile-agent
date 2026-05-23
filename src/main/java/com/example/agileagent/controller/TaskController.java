package com.example.agileagent.controller;

import com.example.agileagent.agent.AgileMasterAgent;
import com.example.agileagent.dto.IssueListDTO;
import com.example.agileagent.dto.TaskIssueDTO;
import com.example.agileagent.service.TaskIssueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/task")
public class TaskController {

    // 把 AI 大脑注入进来
    @Autowired
    private AgileMasterAgent agileMasterAgent;

    // 把 数据库保存服务注入进来
    @Autowired
    private TaskIssueService taskIssueService;

    /**
     * 接收前端发来的会议纪要，并自动提取工单存入数据库
     * 测试路径：POST http://localhost:8080/api/task/process?projectId=1
     */
    @PostMapping("/process")
    public String processMeetingText(
            @RequestParam Long projectId, // 模拟前端传过来的项目ID
            @RequestBody String meetingText // 接收放在请求体里的大段文字
    ) {
        try {
            System.out.println("收到会议纪要，开始呼叫大模型...");

            // 1. 接收包装好的实体类
            IssueListDTO result = agileMasterAgent.extractIssues(meetingText);

            // 2. 从实体类里把真正的 List 拿出来
            List<TaskIssueDTO> issues = result.getIssues();

            // 3. 存入数据库
            taskIssueService.saveIssuesFromAi(projectId, issues);

            return "🎉 成功解析并保存了 " + issues.size() + " 个待办任务！";
        } catch (Exception e) {
            e.printStackTrace();
            return "❌ 报错了，错误原因: " + e.getMessage();
        }
    }

    /**
     * 测试多轮对话记忆
     * 测试路径：POST http://localhost:8080/api/task/chat?projectId=1
     */
    @PostMapping("/chat")
    public String chatWithAgent(
            @RequestParam Long projectId,
            @RequestBody String userMessage
    ) {
        System.out.println("收到聊天消息，查询上下文记忆中...");
        // 直接调用 agent 的 chat 方法，框架会自动把历史记录和新消息一起发给大模型
        return agileMasterAgent.chat(projectId, userMessage);
    }
}