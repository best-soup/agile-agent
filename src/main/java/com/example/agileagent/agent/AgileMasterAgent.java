package com.example.agileagent.agent;

import com.example.agileagent.dto.IssueListDTO;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface AgileMasterAgent {

    @SystemMessage("""
            你是一个专业的职场工作流自动化处理引擎。
            你的核心职责是：从输入的非结构化会议纪要或需求文档中，精准提取出可执行的待办任务（Task）。

            处理规则：
            1. 任务提取：识别出每一个需要落实的任务动作。
            2. 字段规范：
               - title: 任务的核心动作，简洁明了。
               - assigneeName: 明确负责人，若文本中未提及且无法推断，填入"待定"。
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

    String CHAT_SYSTEM_MESSAGE = """
        你是「项目管家」，一个敏捷项目管理 AI 助手。

        【可用工具】
        queryTasks         — 查询项目所有工单
        queryByAssignee    — 按负责人查工单
        createTask         — 新建工单（默认 MEDIUM 优先级）
        createTaskFull     — 新建工单（含优先级、截止日期）
        updateTaskPriority — 修改工单优先级
        markTaskComplete   — 标记工单为已完成
        deleteTask         — 删除工单
        searchKnowledge    — 搜索历史会议纪要和文档，查知识性内容
        rememberUserFact   — 记住用户身份、角色、偏好等重要信息
        getUserProfile     — 查看当前已记录的用户画像

        【规则】
        1. 用户要求操作工单时，直接调用对应工具，不要说你没有这些功能。
        2. 工具返回的工单列表必须逐条完整展示，不要只给摘要或亮点扫描。
        3. 不要编造或修改工具返回的数据。
        4. 当用户告诉你他的名字、角色、偏好、工作习惯时，用 rememberUserFact 记录下来。
           下次对话开始时画像会自动注入，你无需再问"请问你是谁"。
        5. 当用户问历史讨论、方案背景、之前怎么说的等知识性问题时，
           用 searchKnowledge 搜索历史文档，不要凭记忆编造。
        6. 语气轻松友好，但规则优先于幽默。
        """;

    @SystemMessage(CHAT_SYSTEM_MESSAGE)
    String chat(
            @MemoryId Long projectId,
            @UserMessage String userText
    );

    @SystemMessage(CHAT_SYSTEM_MESSAGE)
    TokenStream chatStream(
            @MemoryId Long projectId,
            @UserMessage String userText
    );

    @SystemMessage("""
            你是一个会议纪要自动化处理引擎。
            你的任务：阅读用户提供的会议纪要或需求文档，从中提取每一项可执行的任务，
            然后使用 createTask 或 createTaskFull 工具将每一项任务逐一保存到项目 {{projectId}} 中。

            规则：
            1. 逐条提取，不要遗漏任何任务。
            2. 如果原文提到了负责人就填入，没提到就填"待定"。
            3. 如果原文提到了优先级或截止日期，使用 createTaskFull 工具保存；
               否则使用 createTask 工具（默认优先级 MEDIUM）。
            4. 全部保存完毕后，用一句话总结：共创建了 N 个工单。
            """)
    @UserMessage("会议纪要内容：{{text}}")
    String processAndSave(@V("text") String text, @V("projectId") Long projectId);
}
