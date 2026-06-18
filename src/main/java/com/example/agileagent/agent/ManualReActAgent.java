package com.example.agileagent.agent;

import com.example.agileagent.config.ManualChatMemoryStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手写 ReAct（Reasoning + Acting）执行器。
 *
 * 不依赖 {@code @AiService} 声明式代理，手动实现完整的 ReAct 循环：
 * <pre>
 *   Reasoning: LLM 返回文本 或 function_call
 *   Acting:    手动路由到真实业务 Tool，记录调用参数与耗时
 *   Observation: 工具执行结果回传消息列表，驱动下一轮推理
 * </pre>
 *
 * <h3>与 {@code @AiService} 的关系</h3>
 * 两者做的事完全一样——拼接消息 → 调 LLM → 判断 tool call → 执行工具 → 回填结果 → 循环。
 * 区别仅在于：
 * <ul>
 *   <li>{@code @AiService} 由 LangChain4j 在运行时生成代理类，循环逻辑内置。</li>
 *   <li>{@code ManualReActAgent} 显式手写，每一步可控、可观测、可打日志。</li>
 * </ul>
 */
@Component
public class ManualReActAgent {

    private static final Logger log = LoggerFactory.getLogger(ManualReActAgent.class);
    private static final int MAX_STEPS = 10;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String MEETING_SYSTEM_MESSAGE = """
            你是一个会议纪要自动化处理引擎。
            你的任务是阅读会议纪要，从中提取每一项明确、可执行的行动项，
            然后使用 createTask 或 createTaskFull 工具逐条保存到当前项目。

            【规则】
            1. 只创建会议原文中能够找到依据的任务，不要把讨论、观点或已经完成的事项创建为工单。
            2. 原文明确负责人时填写负责人，否则填写“待定”。
            3. 原文明确优先级或截止日期时使用 createTaskFull，否则使用 createTask。
            4. 相同任务只创建一次，不要重复调用工具。
            5. 不要调用查询、修改、删除、知识检索或用户画像工具。
            6. 全部工具执行完毕后，说明实际成功创建了多少个工单。
            """;

    // LangChain4j auto-configured bean (OpenAiChatModel → 千问 Plus)
    private final ChatLanguageModel chatModel;

    // 真实业务工具
    private final TaskTools taskTools;
    private final KnowledgeTools knowledgeTools;
    private final UserProfileTools userProfileTools;
    private final ManualChatMemoryStore memoryStore;

    /**
     * Tool 注册表：tool_name → (projectId, argumentsJson) → 执行结果字符串
     */
    private final Map<String, ToolExecutor> toolDispatcher = new LinkedHashMap<>();

    /**
     * 所有 Tool 的 Function Calling 定义，传给 LLM
     */
    private final List<ToolSpecification> toolSpecifications = new ArrayList<>();
    private final List<ToolSpecification> meetingToolSpecifications = new ArrayList<>();

    public ManualReActAgent(ChatLanguageModel chatModel,
                            TaskTools taskTools,
                            KnowledgeTools knowledgeTools,
                            UserProfileTools userProfileTools,
                            ManualChatMemoryStore memoryStore) {
        this.chatModel = chatModel;
        this.taskTools = taskTools;
        this.knowledgeTools = knowledgeTools;
        this.userProfileTools = userProfileTools;
        this.memoryStore = memoryStore;
        registerAllTools();
    }

    // ═══════════════════════════════════════════════════════════════════
    // 对外入口
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 以 ReAct 模式执行一轮对话。
     *
     * @param projectId  项目 ID（tool 调用的上下文隔离）
     * @param userInput  用户自然语言输入
     * @return 最终回复 + 完整执行轨迹
     */
    public ReActResult execute(Long projectId, String userInput) {
        return executeChat(projectId, userInput);
    }

    /**
     * 使用独立 Redis 对话记忆执行自然语言工单操作或历史知识检索。
     */
    public ReActResult executeChat(Long projectId, String userInput) {
        ChatMemory memory = memoryStore.get(projectId);
        UserMessage userMessage = new UserMessage(userInput);
        List<ChatMessage> messages = buildChatMessages(projectId, userMessage, memory);
        memory.add(userMessage);

        return runLoop(projectId, userInput, messages, toolSpecifications, memory, "chat");
    }

    /**
     * 使用会议专用提示词执行行动项提取和工单创建。
     * 会议原文是一份独立输入，不混入聊天记忆，避免历史对话污染任务提取。
     */
    public ReActResult processMeeting(Long projectId, String meetingText) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(MEETING_SYSTEM_MESSAGE
                + "\n【当前日期】" + java.time.LocalDate.now()
                + "\n【当前项目】projectId=" + projectId));
        messages.add(new UserMessage("会议纪要内容：\n" + meetingText));

        return runLoop(projectId, meetingText, messages,
                meetingToolSpecifications, null, "meeting");
    }

    private ReActResult runLoop(Long projectId,
                                String userInput,
                                List<ChatMessage> messages,
                                List<ToolSpecification> availableTools,
                                ChatMemory memory,
                                String mode) {
        List<ReActStep> trace = new ArrayList<>();
        Instant start = Instant.now();

        log.info("══════ ReAct 循环开始 mode={} projectId={} userInput={}",
                mode, projectId, userInput);

        for (int step = 0; step < MAX_STEPS; step++) {
            log.info("── ReAct Step {} ──", step);

            // ──── Reasoning ────
            AiMessage aiMessage;
            try {
                aiMessage = chatModel.chat(
                        ChatRequest.builder()
                                .messages(messages)
                                .toolSpecifications(availableTools)
                                .build()
                ).aiMessage();
            } catch (Exception e) {
                log.error("LLM 调用异常 at step {}: {}", step, e.getMessage());
                trace.add(ReActStep.llmError(step, e));
                return new ReActResult("LLM 调用失败: " + e.getMessage(), trace, Duration.between(start, Instant.now()));
            }

            String text = aiMessage.text();
            boolean hasToolCalls = aiMessage.hasToolExecutionRequests();
            log.info("Reasoning: hasToolCalls={} text={}", hasToolCalls,
                    text != null ? text.substring(0, Math.min(100, text.length())) : "(null)");

            // ──── 终止判断：无 tool call → 最终回复 ────
            if (!hasToolCalls) {
                remember(memory, aiMessage);
                trace.add(ReActStep.finalAnswer(step, text));
                Duration total = Duration.between(start, Instant.now());
                log.info("══════ ReAct 结束 step={} totalSteps={} duration={}ms ══════",
                        step, trace.size(), total.toMillis());
                return new ReActResult(text, trace, total);
            }

            // ──── Acting ────
            messages.add(aiMessage); // LLM 返回的 AiMessage（含 function_call）加入历史
            remember(memory, aiMessage);
            int toolIndex = 0;

            for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                toolIndex++;
                Instant toolStart = Instant.now();

                log.info("Acting [{}/{}]: tool={} args={}",
                        toolIndex, aiMessage.toolExecutionRequests().size(),
                        req.name(), req.arguments());

                try {
                    // 真实工具调用
                    String result = dispatchTool(projectId, req);

                    long durationMs = Duration.between(toolStart, Instant.now()).toMillis();
                    log.info("Observation: tool={} duration={}ms result={}",
                            req.name(), durationMs,
                            result != null ? result.substring(0, Math.min(100, result.length())) : "(null)");

                    trace.add(ReActStep.toolSuccess(step, toolIndex, req, result, durationMs));

                    // ──── Observation ────
                    ToolExecutionResultMessage resultMessage =
                            new ToolExecutionResultMessage(req.id(), req.name(), result);
                    messages.add(resultMessage);
                    remember(memory, resultMessage);

                } catch (Exception e) {
                    long durationMs = Duration.between(toolStart, Instant.now()).toMillis();
                    String errorMsg = "工具 [" + req.name() + "] 执行异常: " + e.getClass().getSimpleName()
                            + " - " + (e.getMessage() != null ? e.getMessage() : "(无异常消息)");

                    log.error("Observation ERROR: tool={} duration={}ms error={}", req.name(), durationMs, errorMsg, e);

                    trace.add(ReActStep.toolError(step, toolIndex, req, errorMsg, durationMs, e));

                    // 异常信息也回传 LLM，让它知道工具失败了并自主调整策略
                    ToolExecutionResultMessage errorMessage =
                            new ToolExecutionResultMessage(req.id(), req.name(), "ERROR: " + errorMsg);
                    messages.add(errorMessage);
                    remember(memory, errorMessage);
                }
            }
            // 循环继续：Observation 已在 messages 中，回到 Reasoning
        }

        // 安全阀：超过最大步数
        String fallback = "[系统] ReAct 循环已达到上限 " + MAX_STEPS + " 步，已强制终止。"
                + "请尝试缩小问题范围或检查工单数量。";
        trace.add(ReActStep.maxStepsExceeded(MAX_STEPS));
        log.warn("ReAct 达到最大步数限制 maxSteps={}", MAX_STEPS);
        remember(memory, new AiMessage(fallback));
        return new ReActResult(fallback, trace, Duration.between(start, Instant.now()));
    }

    // ═══════════════════════════════════════════════════════════════════
    // 工具注册与分发
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 手动注册全部 10 个 Tool（7 工单 + 2 画像 + 1 知识检索）。
     * 每个 Tool 包含 Function Calling 的 JSON Schema 定义 + Java 执行回调。
     */
    private void registerAllTools() {
        // ── TaskTools ──
        register("queryTasks",
                "查询指定项目下的所有工单任务，返回工单列表（含ID、标题、负责人、优先级、截止日期、完成状态）",
                b -> b.addIntegerProperty("projectId", "项目ID")
                      .required("projectId"),
                (projectId, args) -> taskTools.queryTasks(args.get("projectId").asLong()));

        register("queryByAssignee",
                "按负责人姓名查询项目下的工单，返回该负责人名下的所有工单",
                b -> b.addIntegerProperty("projectId", "项目ID")
                      .addStringProperty("assigneeName", "负责人姓名")
                      .required("projectId", "assigneeName"),
                (projectId, args) -> taskTools.queryByAssignee(
                        args.get("projectId").asLong(),
                        args.get("assigneeName").asText()));

        register("createTask",
                "在指定项目下创建新的工单任务（默认 MEDIUM 优先级），返回创建成功的工单ID",
                b -> b.addIntegerProperty("projectId", "项目ID")
                      .addStringProperty("title", "任务标题")
                      .addStringProperty("assigneeName", "负责人姓名")
                      .addStringProperty("description", "任务描述")
                      .required("projectId", "title", "assigneeName", "description"),
                (projectId, args) -> taskTools.createTask(
                        args.get("projectId").asLong(),
                        args.get("title").asText(),
                        args.get("assigneeName").asText(),
                        args.get("description").asText()));

        register("createTaskFull",
                "创建带完整字段的工单任务（含优先级和截止日期）",
                b -> b.addIntegerProperty("projectId", "项目ID")
                      .addStringProperty("title", "任务标题")
                      .addStringProperty("assigneeName", "负责人姓名")
                      .addStringProperty("description", "任务描述")
                      .addEnumProperty("priority", List.of("HIGH", "MEDIUM", "LOW"), "优先级")
                      .addStringProperty("deadlineStr", "截止日期 YYYY-MM-DD")
                      .required("projectId", "title", "assigneeName", "description", "priority", "deadlineStr"),
                (projectId, args) -> taskTools.createTaskFull(
                        args.get("projectId").asLong(),
                        args.get("title").asText(),
                        args.get("assigneeName").asText(),
                        args.get("description").asText(),
                        args.get("priority").asText(),
                        args.get("deadlineStr").asText()));

        register("updateTaskPriority",
                "更新指定工单的优先级",
                b -> b.addIntegerProperty("taskId", "工单ID")
                      .addEnumProperty("newPriority", List.of("HIGH", "MEDIUM", "LOW"), "新优先级")
                      .required("taskId", "newPriority"),
                (projectId, args) -> taskTools.updateTaskPriority(
                        args.get("taskId").asLong(),
                        args.get("newPriority").asText()));

        register("markTaskComplete",
                "将指定工单标记为已完成",
                b -> b.addIntegerProperty("taskId", "工单ID")
                      .required("taskId"),
                (projectId, args) -> taskTools.markTaskComplete(
                        args.get("taskId").asLong()));

        register("deleteTask",
                "删除指定工单",
                b -> b.addIntegerProperty("taskId", "工单ID")
                      .required("taskId"),
                (projectId, args) -> taskTools.deleteTask(
                        args.get("taskId").asLong()));

        // ── KnowledgeTools ──
        register("searchKnowledge",
                "搜索历史会议纪要和需求文档中的相关内容，用于回答关于历史讨论、决策记录、方案背景等知识性问题。返回语义最相关的文档片段（最多3条）",
                b -> b.addIntegerProperty("projectId", "项目ID")
                      .addStringProperty("query", "搜索关键词或自然语言问题")
                      .required("projectId", "query"),
                (projectId, args) -> knowledgeTools.searchKnowledge(
                        args.get("projectId").asLong(),
                        args.get("query").asText()));

        // ── UserProfileTools ──
        register("rememberUserFact",
                "记住关于当前用户的重要信息，如姓名、角色、偏好、工作习惯等。新信息会追加到已有画像中",
                b -> b.addIntegerProperty("projectId", "项目ID")
                      .addStringProperty("fact", "关于用户的事实信息，例如'用户名叫张三，是前端负责人'")
                      .required("projectId", "fact"),
                (projectId, args) -> userProfileTools.rememberUserFact(
                        args.get("projectId").asLong(),
                        args.get("fact").asText()));

        register("getUserProfile",
                "查看当前已记住的用户画像信息",
                b -> b.addIntegerProperty("projectId", "项目ID")
                      .required("projectId"),
                (projectId, args) -> userProfileTools.getUserProfile(
                        args.get("projectId").asLong()));

        log.info("ManualReActAgent 已注册 {} 个 Tool", toolDispatcher.size());
    }

    /**
     * 注册单个 Tool：用 {@link JsonObjectSchema} Builder 构建 Function Calling JSON Schema，
     * 绑定执行函数。
     */
    private void register(String name, String description,
                          java.util.function.Function<JsonObjectSchema.Builder, JsonObjectSchema.Builder> schemaBuilder,
                          ToolExecutor executor) {
        JsonObjectSchema.Builder b = JsonObjectSchema.builder().description(description);
        JsonObjectSchema params = schemaBuilder.apply(b).build();

        ToolSpecification spec = ToolSpecification.builder()
                .name(name)
                .description(description)
                .parameters(params)
                .build();

        toolSpecifications.add(spec);
        if ("createTask".equals(name) || "createTaskFull".equals(name)) {
            meetingToolSpecifications.add(spec);
        }
        toolDispatcher.put(name, executor);
    }

    /**
     * 根据 LLM 返回的 ToolExecutionRequest 路由到真实的 Java 方法。
     */
    private String dispatchTool(Long projectId, ToolExecutionRequest req) throws Exception {
        String toolName = req.name();
        ToolExecutor executor = toolDispatcher.get(toolName);
        if (executor == null) {
            String msg = "未知工具: " + toolName + "（已注册: " + toolDispatcher.keySet() + "）";
            log.warn(msg);
            return "ERROR: " + msg;
        }
        JsonNode args = objectMapper.readTree(req.arguments());
        log.debug("dispatchTool: {} args={}", toolName, args);
        return executor.execute(projectId, args);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 消息构造
    // ═══════════════════════════════════════════════════════════════════

    private List<ChatMessage> buildChatMessages(
            Long projectId, UserMessage userMessage, ChatMemory memory) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(AgileMasterAgent.CHAT_SYSTEM_MESSAGE
                + "\n\n【当前项目上下文】projectId=" + projectId));

        String profile = memoryStore.getUserProfile(projectId);
        if (profile != null && !profile.isBlank()) {
            messages.add(new SystemMessage(
                    "[以下关于当前用户的已知信息，请基于这些信息提供个性化回复]\n" + profile));
        }

        messages.addAll(memory.messages());
        messages.add(userMessage);
        return messages;
    }

    private void remember(ChatMemory memory, ChatMessage message) {
        if (memory != null) {
            memory.add(message);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 内部类型
    // ═══════════════════════════════════════════════════════════════════

    /** Tool 执行回调 */
    @FunctionalInterface
    private interface ToolExecutor {
        String execute(Long projectId, JsonNode args) throws Exception;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 结果模型（public，可供 Controller 直接序列化）
    // ═══════════════════════════════════════════════════════════════════

    /** ReAct 完整执行结果 */
    public static class ReActResult {
        private final String answer;
        private final List<ReActStep> trace;
        private final long totalDurationMs;

        ReActResult(String answer, List<ReActStep> trace, Duration duration) {
            this.answer = answer;
            this.trace = trace;
            this.totalDurationMs = duration.toMillis();
        }

        public String getAnswer() { return answer; }
        public List<ReActStep> getTrace() { return trace; }
        public long getTotalDurationMs() { return totalDurationMs; }
        public int getTotalSteps() { return trace.size(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("═══════════════════════════════════\n");
            sb.append("ReAct 执行结果\n");
            sb.append("总步数: ").append(getTotalSteps()).append("\n");
            sb.append("总耗时: ").append(totalDurationMs).append("ms\n");
            sb.append("═══════════════════════════════════\n\n");
            for (int i = 0; i < trace.size(); i++) {
                sb.append("Step ").append(i + 1).append("\n");
                sb.append(trace.get(i).toString());
                sb.append("\n");
            }
            sb.append("═══════════════════════════════════\n");
            sb.append("最终回复:\n").append(answer);
            return sb.toString();
        }
    }

    /** ReAct 单步记录 */
    public static class ReActStep {
        private final int loopIndex;       // 第几轮 ReAct 循环
        private final int toolIndex;       // 本轮中第几个 tool（从 1 开始，final answer = 0）
        private final StepType type;
        private final String toolName;
        private final String arguments;    // LLM 传入的原始 JSON
        private final String observation;  // 工具返回值 或 异常信息 或 最终文本
        private final long durationMs;
        private final String errorType;    // 异常类名，成功时为 null

        private ReActStep(int loopIndex, int toolIndex, StepType type,
                          String toolName, String arguments, String observation,
                          long durationMs, String errorType) {
            this.loopIndex = loopIndex;
            this.toolIndex = toolIndex;
            this.type = type;
            this.toolName = toolName;
            this.arguments = arguments;
            this.observation = observation;
            this.durationMs = durationMs;
            this.errorType = errorType;
        }

        static ReActStep finalAnswer(int loopIndex, String text) {
            return new ReActStep(loopIndex, 0, StepType.FINAL_ANSWER, null, null,
                    text, 0, null);
        }

        static ReActStep toolSuccess(int loopIndex, int toolIndex,
                                     ToolExecutionRequest req, String result, long durationMs) {
            return new ReActStep(loopIndex, toolIndex, StepType.TOOL_SUCCESS,
                    req.name(), req.arguments(), result, durationMs, null);
        }

        static ReActStep toolError(int loopIndex, int toolIndex,
                                   ToolExecutionRequest req, String errorMsg, long durationMs, Exception e) {
            return new ReActStep(loopIndex, toolIndex, StepType.TOOL_ERROR,
                    req.name(), req.arguments(), errorMsg, durationMs, e.getClass().getSimpleName());
        }

        static ReActStep llmError(int loopIndex, Exception e) {
            return new ReActStep(loopIndex, 0, StepType.LLM_ERROR, null, null,
                    e.getMessage(), 0, e.getClass().getSimpleName());
        }

        static ReActStep maxStepsExceeded(int maxSteps) {
            return new ReActStep(maxSteps, 0, StepType.MAX_STEPS, null, null,
                    "达到最大步数限制 " + maxSteps, 0, null);
        }

        // ── getters ──
        public int getLoopIndex() { return loopIndex; }
        public int getToolIndex() { return toolIndex; }
        public StepType getType() { return type; }
        public String getToolName() { return toolName; }
        public String getArguments() { return arguments; }
        public String getObservation() { return observation; }
        public long getDurationMs() { return durationMs; }
        public String getErrorType() { return errorType; }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("  Loop=").append(loopIndex);
            if (toolIndex > 0) sb.append(" Tool#").append(toolIndex);
            sb.append(" Type=").append(type);
            sb.append(" Duration=").append(durationMs).append("ms");
            if (toolName != null) sb.append("\n  Tool: ").append(toolName);
            if (arguments != null) sb.append("\n  Args: ").append(arguments);
            if (observation != null) {
                String obs = observation.length() > 200 ? observation.substring(0, 200) + "..." : observation;
                sb.append("\n  Result: ").append(obs);
            }
            if (errorType != null) sb.append("\n  ErrorType: ").append(errorType);
            return sb.toString();
        }

        public enum StepType {
            FINAL_ANSWER,   // LLM 返回了文本（无 tool call），循环终止
            TOOL_SUCCESS,   // 工具调用成功
            TOOL_ERROR,     // 工具调用抛出异常
            LLM_ERROR,      // LLM 调用本身失败
            MAX_STEPS       // 循环达到最大步数限制
        }
    }
}
