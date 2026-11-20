# Agile Agent 项目现阶段总结

更新时间：2026-11-20

## 一句话结论

这是一个已经打通核心 Agent 业务闭环的 Java AI 应用原型，但还不是可以直接接入真实业务数据的生产级服务。

当前最合理的定位是：

> 基于 Spring Boot 与 LangChain4j 的智能项目管理 Agent 原型，聚焦会议行动项转工单、自然语言工单操作和历史决策追溯。

项目目前不需要更换语言或推倒重做架构。下一阶段的重点应该从“继续堆 AI 功能”转向“补齐可信后端工程能力”。

## 当前已实现能力

- 使用 LangChain4j 接入千问 Plus，支持声明式 `@AiService`。
- 使用 Function Calling 实现工单查询、创建、修改、完成和删除。
- 实现手写 ReAct 循环，支持 Reasoning、Tool Calling、Observation 和最大步数保护。
- 使用 Redis 持久化多轮对话消息。
- 手动序列化和反序列化 `UserMessage`、`SystemMessage`、`AiMessage` 和 `ToolExecutionResultMessage`。
- 使用 Redis 保存用户画像，并在对话上下文中注入。
- 使用 Embedding + Qdrant 实现会议纪要和文档的语义检索。
- 支持 `.txt`、`.md` 和 `.docx` 文档解析。
- 使用 SSE 提供流式对话接口。
- 提供基础的 RAG 评估和 Agent 对比脚本。

## 完成度评估

| 维度 | 完成度 | 当前判断 |
|---|---:|---|
| 业务闭环 | 75% | 会议文本、工单操作、知识检索、记忆和流式输出已经串通 |
| Agent 能力 | 70% | 有 Tool Calling、手写 ReAct、工具轨迹和错误回传 |
| RAG 能力 | 55% | 有索引、分块、Embedding 和 Qdrant，但检索隔离和质量保障仍需加强 |
| Java 后端工程 | 35% | 已具备基础项目权限隔离，仍缺事务、幂等、审计、统一异常和并发控制 |
| 测试与交付 | 20% | 已有 Spring 启动测试和权限边界单测，缺少真实依赖集成测试和一键部署环境 |
| 生产可用性 | 30% | 可以演示和面试展示，不适合直接处理真实业务数据 |

综合判断：

- PoC 完成度：约 65%～70%。
- 生产级完成度：约 30%。
- 面试展示完成度：约 60%～70%。

任务业务已统一通过 `TaskIssueService` 访问，并新增项目范围内的任务 REST CRUD、分页筛选和 H2 业务测试。自动化测试不能替代真实服务及生产并发验证。

## 当前最重要的问题

### 1. 身份和项目隔离（基础隔离已完成，真实链路仍待验证）

本阶段已增加 `app_user`、`agile_project`、`project_member` 模型、Bearer API 凭证认证和 OWNER/ADMIN/MEMBER/VIEWER 角色校验。Controller、Agent Tool、RAG 索引/检索入口、Redis 画像和会话记忆均要求当前认证用户具备项目权限；修改、完成和删除任务会再次验证工单属于当前项目。

用户画像和会话记忆键已改为 `userId:projectId` 作用域，避免不同用户在同一项目或不同项目之间串用。

当前仍是轻量 API Key 认证，不等同于完整 OAuth/JWT/密码登录系统；数据库初始化和凭证轮换也需要后续补齐。

### 2. 高风险操作缺乏保护

删除和修改操作已经有基础项目权限检查，但没有二次确认、审计日志或幂等键。会议纪要处理会直接逐条创建任务，模型识别错误或请求重试都可能污染数据库。

如果执行到中途失败，可能留下部分已创建任务，没有事务和补偿机制。

### 3. 并发和可靠性不足

Redis 记忆采用读、改、写的非原子流程，并发请求可能互相覆盖消息。

SSE 每个请求直接创建一个新线程，没有受控线程池、取消机制、客户端断连清理、心跳和背压。

`/process` 是同步接口，并且先调用 Embedding 再调用 LLM，外部依赖的延迟或失败会直接阻塞请求。

### 4. RAG 仍属于实验版本

当前检索是先从全库取 top-k，再按项目过滤，可能因为其他项目占据 top-k 而丢失当前项目的相关结果。

文档重复提交会重复写入向量库；分块逻辑在跨段落 overlap、超长无标点文本和日期清洗方面仍有边界问题。

### 5. 测试和交付材料不足

仓库缺少 README、Docker Compose、测试数据和完整接口说明；数据库结构已通过 Flyway 管理，并保留可重复执行的手工初始化脚本 `src/main/resources/db/schema.sql`。`notes/`、`local-benchmarks/` 和 `demo-data/` 被 `.gitignore` 排除，实验记录和评估数据不会随 Git 交付。

### 数据库迁移与初始化

数据库迁移入口是 `src/main/resources/db/migration`：

- `V1__baseline_schema.sql` 创建四张完整业务表：`app_user`、`agile_project`、`project_member`、`task_issue`。
- `V2__Harden_schema.java` 先检查现有四张表和 `task_issue` 的实体字段，再补工单查询索引和必要外键；缺少既有字段时直接失败，不自动猜测或新增业务字段。
- `spring.flyway.baseline-on-migrate=true` 兼容已有无 Flyway 历史的数据库：已有表会建立版本 1 基线，然后执行 V2；全新数据库会依次执行 V1、V2。
- Flyway 禁止 `clean`。重复启动只执行未完成的版本迁移，不删除表、不清空数据。

全新数据库初始化：

1. 创建空的 `agile_agent_db` 数据库。
2. 设置 `DB_PASSWORD`，启动 Spring Boot：`.\mvnw.cmd spring-boot:run`。
3. Flyway 自动创建四张表并补索引、外键。

已有数据库升级：

1. 先备份数据库，并确认 `task_issue` 包含实体要求的九个字段。
2. 设置同一数据库连接配置，启动应用。
3. Flyway 自动建立基线并执行 V2；若发现字段、表或历史数据不满足约束，应用会失败并保留原数据，需人工检查后再处理。

不希望应用自动迁移时，可设置 `SPRING_FLYWAY_ENABLED=false`；手工初始化/核对可执行 `src/main/resources/db/schema.sql`，该脚本使用 `CREATE TABLE IF NOT EXISTS`，重复执行不会删除已有表或数据。

## 项目真正值得展示的地方

- 不是只调用聊天接口，而是完成了模型、工具、数据库和记忆之间的闭环。
- 手写 ReAct 循环让 Agent 的每一步、工具参数、执行结果和耗时都可观测。
- 解决了带 Tool Calling 的 `AiMessage` 和 `ToolExecutionResultMessage` Redis 持久化问题。
- 具备 Agentic RAG 的基本形态，让 Agent 自主判断是否检索历史知识。
- 有分块、Embedding、RAG 评估和 Agent 对比实验，说明你开始关注效果和性能，而不是只关注功能演示。
- 项目文档已经能够区分当前实现和后续规划，这一点比夸大项目成熟度更重要。

## 下一阶段路线（本阶段完成：基础身份、项目和成员隔离）

### 阶段 1 收尾（先于下一阶段功能开发）

1. 已完成 MySQL、Redis、Qdrant 的真实 smoke test。
2. 已完成用户、项目、成员和 Viewer 写入拒绝的 API 权限测试。
3. 已核对四张业务表结构，接入 Flyway 迁移，并补充可重复执行的初始化说明。

### 第一优先级：可信业务闭环

1. 已将 Task Tool 和 AI 批量任务写入统一收口到任务 Service，并补充任务 REST CRUD、分页和事务边界。
2. 增加幂等键、失败状态和审计日志，再实现任务草稿、人工确认和批量提交。
3. 为 API Key 增加轮换、撤销和更安全的生产认证方案。

### 第二优先级：基础设施可靠性

1. 为 RAG 增加向量库原生 metadata filter、文档 ID、去重和来源引用。
2. 修复 Redis 并发读改写问题，或改为原子追加/按消息存储。
3. 使用受控线程池重做 SSE 生命周期。
4. 增加统一异常处理、超时、重试和取消机制。

### 第三优先级：可验证和可交付

1. 使用 Testcontainers 覆盖 MySQL、Redis 和 Qdrant 集成测试。
2. 为 Tool、Redis 序列化、RAG 分块和 SSE 增加单元测试。
3. 补充 README、SQL、Docker Compose、环境变量说明和接口示例。
4. 更新简历和项目文档，确保工具数量、模型版本和已实现能力与代码一致。

## 语言和架构决策

### Java

继续作为主语言。当前项目已经积累了 Spring Boot、MyBatis-Plus、Redis、SSE、并发和 LangChain4j 经验，这些对 Java 后端和企业 AI 应用岗位都有价值。

### Python

建议作为辅助语言学习，不要迁移当前项目。可以用 Python 补充 FastAPI、Pydantic、LangGraph、LlamaIndex 和 RAG 评估能力，但没有必要为了换语言而重写已有系统。

### 架构

暂时不要引入微服务、Kafka、Kubernetes 或复杂的多 Agent 编排。当前瓶颈不是架构规模，而是权限、数据一致性、并发和测试。

## 实习投递定位

当前可以开始投递：

- Java 后端开发实习。
- AI 应用开发实习。
- Agent、RAG、LLM 工具调用相关的后端岗位。

暂时不宜把自己定位为：

- 大模型训练方向。
- AI Infra 或分布式推理平台方向。
- 已经具备生产级多 Agent 平台经验。

推荐简历表述：

> 基于 Spring Boot 与 LangChain4j 实现智能项目管理 Agent，支持会议行动项提取、工单工具调用、Redis 多轮记忆、Agentic RAG 历史知识检索和 SSE 流式响应。当前版本聚焦 Agent 与业务系统交互闭环，并持续完善权限、人工确认、幂等和自动化测试。

## 面试时的诚实边界

- 当前是核心能力原型，不是完整的项目管理平台。
- `@AiService` 的 ReAct 循环主要由框架完成，手写版本用于理解、控制和观测。
- 当前代码只有千问 Plus 配置；DeepSeek 仅出现在历史调试记录中，没有完整的多模型切换实现。
- 当前实际注册 10 个 Tool，旧文档中“8 个 Tool”的说法需要修正。
- 当前检索、记忆和 SSE 已经可以演示，但还没有经过真实并发和故障场景验证。

## 最终路线判断

不换主语言，不推倒重来，不继续无止境堆功能。

下一阶段把项目从“能跑的 Agent Demo”推进到“有基础权限、可回滚、可测试、可解释的 Java AI 后端服务”，然后继续投递 Java 后端和 AI 应用岗位。

当前最需要提升的不是技术名词数量，而是让 Agent 在面对真实数据和失败场景时仍然可控。
