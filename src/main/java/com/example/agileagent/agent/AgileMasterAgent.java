package com.example.agileagent.agent;


import com.example.agileagent.dto.IssueListDTO;
import com.example.agileagent.dto.TaskIssueDTO;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

import java.util.List;

@AiService // 告诉 Spring Boot，这是一个 AI 代理接口
public interface AgileMasterAgent {

    @SystemMessage("""
            你是一个专业的职场工作流自动化处理引擎。
            你的核心职责是：从输入的非结构化会议纪要或需求文档中，精准提取出可执行的待办任务（Task）。
                
            处理规则：
            1. 任务提取：识别出每一个需要落实的任务动作。
            2. 字段规范：
               - title: 任务的核心动作，简洁明了。
               - assigneeName: 明确负责人，若文本中未提及且无法推断，填入“待定”。
               - description: 任务背景说明，不要包含无用的社交辞令。
               - priority: 默认为 "MEDIUM"，若文本有紧急程度描述，根据逻辑转为 "HIGH" 或 "LOW"。
               - deadline: 统一输出为 "YYYY-MM-DD" 格式。若未提及，请返回 null。
                
            输出约束：
            - 严禁输出任何 Markdown 格式或解释性的废话（如 "好的，这是我提取的结果..."）。
            - 你必须且只能输出如下结构的 JSON 对象，不要带任何 Markdown 标记（如 ```json），不要包含任何多余的解释文字：
            {
              "issues": [
             {
              "title": "任务简短标题",
              "assigneeName": "负责人姓名或'待定'",
              "description": "任务背景描述",
              "priority": "HIGH/MEDIUM/LOW",
              "deadline": "YYYY-MM-DD"
                }
              ]
            }
            """)
    @UserMessage("请根据这段文本分析并提取任务工单: {{text}}")
    IssueListDTO extractIssues(@V("text") String text);

    // ==========================================
    // 🌟 新增：多轮对话能力
    // ==========================================
    @SystemMessage("你是一个敏捷项目管理的 AI 助手，你的名字叫'项目管家'。请用简短、幽默的语气和用户对话。")
    String chat(
            @MemoryId Long projectId, // 🌟 核心：这个注解会自动把聊天记录和 projectId 绑定！
            @UserMessage String userText
    );
}
