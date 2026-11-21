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
| Java 后端工程 | 60% | 已具备项目权限隔离、任务事务、草稿确认、幂等提交、审计、API Key 生命周期、统一错误响应和基础并发控制；真实分布式环境仍待验证 |
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

当前仍是轻量 API Key 认证，不等同于完整 OAuth/JWT/密码登录系统。API Key 已拆为独立 `api_key` 生命周期表，支持多 Key、过期、撤销和轮换；`app_user.api_key_hash` 作为迁移期兼容列保留，认证已不再查询该列，后续可在确认所有旧库完成迁移后移除。

### 2. 高风险操作已有草稿确认闭环

会议纪要提取结果先保存为项目内任务草稿，允许草稿创建者或项目管理员编辑、删除草稿项并确认；确认后才会以事务批量创建正式任务。提交使用幂等键，记录提交结果、失败状态和项目审计日志。数据库失败时正式任务批次回滚，草稿转为 FAILED 并可用原幂等键重试。

该流程已由 H2 服务测试验证；真实 MySQL 迁移和外部 LLM/RAG 端到端链路仍需单独验收。

### 3. 并发和可靠性（基础控制已补齐，真实环境仍待验证）

Redis 对话记忆已改为版本化 Redis List，单条消息通过 `RPUSH + LTRIM + EXPIRE` 原子脚本追加；旧整体 JSON key 保留兼容读取并在首次写入时按会话迁移。不同用户/项目仍使用独立 key。

SSE 已改为受控 `ThreadPoolTaskExecutor`，设置核心/最大线程数、队列容量和拒绝策略；增加完成、超时、错误、客户端断开和发送失败清理，并显式传递和清理用户、项目、requestId 上下文。

LangChain4j Tool 边界使用 `@ToolMemoryId` 接收带上下文的 Agent memoryId，在 `TaskTools`、知识检索和用户画像 Tool 执行前显式恢复 `userId/projectId/requestId`，执行后清理；不依赖普通 ThreadLocal 自动跨线程传播。ChatMemory 使用规范化后的 `userId:projectId` 存储键，保持原会话隔离语义。

草稿提交使用数据库条件更新抢占 `CONFIRMED/FAILED → SUBMITTING`，两个并发请求不会重复创建正式任务；模型连接/读取超时为 30 秒且关闭自动重试，避免写操作因模型重试重复执行。

当前仍未在真实 Redis、生产 Servlet 容器和多实例 MySQL 环境验证故障恢复、网络断开和跨实例时序。

### 4. RAG 仍属于实验版本

当前检索是先从全库取 top-k，再按项目过滤，可能因为其他项目占据 top-k 而丢失当前项目的相关结果。

文档重复提交会重复写入向量库；分块逻辑在跨段落 overlap、超长无标点文本和日期清洗方面仍有边界问题。

### 5. 测试和交付材料不足

仓库缺少 README、Docker Compose、测试数据和完整接口说明；数据库结构已通过 Flyway 管理，并保留可重复执行的手工初始化脚本 `src/main/resources/db/schema.sql`。`notes/`、`local-benchmarks/` 和 `demo-data/` 被 `.gitignore` 排除，实验记录和评估数据不会随 Git 交付。

### 数据库迁移与初始化

数据库迁移入口是 `src/main/resources/db/migration`：

- `V1__baseline_schema.sql` 创建四张完整业务表：`app_user`、`agile_project`、`project_member`、`task_issue`。
- `V2__Harden_schema.java` 先检查现有四张表和 `task_issue` 的实体字段，再补工单查询索引和必要外键；缺少既有字段时直接失败，不自动猜测或新增业务字段。
- `V3__task_drafts_and_audit.sql` 新增任务草稿、草稿项和审计日志表，不修改已执行的 V1/V2。
- `V4__api_key_lifecycle.sql` 新增独立 API Key 表，并把已有 `app_user.api_key_hash` 回填为 ACTIVE Key；旧列改为可空并进入弃用期，不影响已有用户和项目关系。
- `spring.flyway.baseline-on-migrate=true` 兼容已有无 Flyway 历史的数据库：已有表会建立版本 1 基线，然后执行 V2；全新数据库会依次执行 V1、V2。
- Flyway 禁止 `clean`。重复启动只执行未完成的版本迁移，不删除表、不清空数据。

全新数据库初始化：

1. 创建空的 `agile_agent_db` 数据库。
2. 设置 `DB_PASSWORD`，启动 Spring Boot：`.\mvnw.cmd spring-boot:run`。
3. Flyway 自动创建八张业务表并补索引、外键。

已有数据库升级：

1. 先备份数据库，并确认 `task_issue` 包含实体要求的九个字段。
2. 设置同一数据库连接配置，启动应用。
3. Flyway 自动建立基线并执行尚未应用的 V2、V3、V4；若发现字段、表或历史数据不满足约束，应用会失败并保留原数据，需人工检查后再处理。

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
2. 已实现幂等键、失败状态、审计日志、任务草稿、人工确认和事务性批量提交。
3. 已实现独立 API Key、多 Key 管理、过期、撤销、轮换和一次性明文返回；更安全的生产认证方案仍待后续评估。

### 第二优先级：基础设施可靠性

1. 为 RAG 增加向量库原生 metadata filter、文档 ID、去重和来源引用。
2. 修复 Redis 并发读改写问题，或改为原子追加/按消息存储。
3. 使用受控线程池重做 SSE 生命周期。
4. 已增加统一错误响应、字段校验、requestId 响应头/MDC 关联、安全认证错误输出、受控 SSE 线程池、Redis 原子记忆追加、Tool 上下文恢复和草稿提交竞态保护。

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
