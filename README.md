# Agile Agent

基于 Spring Boot 和 LangChain4j 的智能项目管理 Agent 原型：导入会议记录，索引知识库并追溯来源，提取任务草稿，经人工确认后提交正式任务。用户通过 API Key 认证，项目成员权限限制任务和知识访问。

## 架构

- Spring Boot 提供 REST、Agent Tool 和 SSE；MySQL 保存用户、项目、成员、任务、草稿、会议记录和审计。
- Flyway 自动迁移 MySQL；Redis 保存会话记忆；Qdrant 保存带项目及来源 metadata 的向量；DashScope 提供聊天和 Embedding。
- `MeetingRecord` 保存导入内容，RAG 文档以会议记录为来源；用户显式生成 `TaskDraft`，确认并提交后创建 `TaskIssue`。

## 前置条件

Java 17、Docker Desktop（Engine 已启动）、DashScope API Key。项目自带 Maven Wrapper，不需要单独安装 Maven。不要将密钥、实际会议内容或 `.env` 提交到仓库。

## 启动

1. 将 `.env.example` 复制为 `.env`，设置本地 MySQL 密码；不要提交 `.env`。
2. 在仓库目录启动依赖：

```powershell
docker compose up -d
docker compose ps
Invoke-RestMethod http://localhost:16333/healthz
```

默认仅绑定本机：MySQL `13306`、Redis `16379`、Qdrant HTTP `16333`、Qdrant gRPC `16334`。端口可在 `.env` 修改；示例中的端口与下方环境变量需保持一致。MySQL 和 Redis 有容器健康检查；Qdrant 镜像不保证提供 shell 或 curl，使用上面的 HTTP `/healthz` 检查。

3. 首次使用前在 Qdrant 创建 1024 维 Cosine collection（仅在不存在时执行，不要覆盖已有 collection）：

```powershell
Invoke-RestMethod -Method Put -Uri http://localhost:16333/collections/agile-agent-docs -ContentType 'application/json' -Body '{"vectors":{"size":1024,"distance":"Cosine"}}'
```

4. 在当前 PowerShell 设置环境变量并启动本地应用：

```powershell
$env:DB_PASSWORD = '<与 .env 中相同的 MySQL 密码>'
$env:DASHSCOPE_API_KEY = '<百炼 API Key>'
$env:DB_URL = 'jdbc:mysql://localhost:13306/agile_agent_compose?useUnicode=true&characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true'
$env:DB_USERNAME = 'root'
$env:REDIS_HOST = 'localhost'
$env:REDIS_PORT = '16379'
$env:QDRANT_HOST = 'localhost'
$env:QDRANT_PORT = '16334'
$env:QDRANT_COLLECTION = 'agile-agent-docs'
.\mvnw.cmd spring-boot:run
```

使用 Compose 默认数据库名之外的名称时，调整 JDBC URL。Flyway 在启动时自动执行未应用的 V1-V6；已有数据库先备份。直接使用本机已有依赖时，可省略地址覆盖，默认连接 MySQL `localhost:3306/agile_agent_db`、Redis `6379` 和 Qdrant gRPC `6334`。`DB_PASSWORD`、`DASHSCOPE_API_KEY` 必须由环境提供。

`docker compose stop` 或 `docker compose down` 保留 named volume。**完全清理本项目容器和测试数据卷**使用 `docker compose down --volumes`，会删除 Compose 管理的 MySQL、Redis、Qdrant 数据，执行前先备份；不会删除本机独立运行的数据库。

## 最小 API 流程

1. `POST /api/users`，JSON `{"username":"demo-user","displayName":"Demo"}`。响应中的 `apiKey` 只返回一次，安全保存，不要写入仓库。
2. 后续请求带 `Authorization: Bearer <API_KEY>`，调用 `POST /api/projects`，JSON `{"name":"Demo Project"}`，取得项目 ID。
3. `POST /api/projects/{projectId}/meeting-records/text`，JSON 例如 `{"title":"测试会议","content":"测试会议行动项：完成接口测试。"}`；也可通过 `/files` 的 multipart `file` 上传 TXT、Markdown 或 DOCX。查看返回的 `indexStatus`。
4. `POST /api/projects/{projectId}/meeting-records/{recordId}/task-drafts`，取得响应的 `draft.id`；检查并编辑草稿项。
5. `POST /api/projects/{projectId}/task-drafts/{draftId}/confirm`；然后向 `/submit` POST `{"idempotencyKey":"demo-submit-001"}`。
6. `GET /api/projects/{projectId}/tasks?page=1&size=20` 查询正式任务。不同草稿使用不同幂等键。

Swagger UI：`http://localhost:8080/swagger-ui/index.html`；OpenAPI JSON：`http://localhost:8080/v3/api-docs`。创建用户接口不需要认证，其他业务接口使用 Swagger 的 Authorize 输入 API Key。Swagger 中不要填入示例以外的真实凭据并提交截图或文件。

## 健康与测试

- `/actuator/health/liveness` 表示应用存活；`/actuator/health/readiness` 检查应用状态、MySQL 和 Redis；`/actuator/health` 是总体状态。响应隐藏依赖详情。
- Qdrant 与 DashScope 不在 readiness 中：分别通过 Qdrant `/healthz` 和一次授权的业务请求验证；外部不可达时 RAG/模型请求可能失败，不代表 liveness 失败。
- 普通回归：`.\mvnw.cmd test`，不需要 Docker 或真实模型。
- MySQL/Redis 容器测试：`.\mvnw.cmd -Pintegration verify`，需要运行中的 Docker 和可拉取 `mysql:8.4`、`redis:7.4-alpine` 的网络；测试使用临时容器，不连接开发库。Docker 不可用时命令失败，请检查 Docker 状态与镜像拉取日志。
- 可选 Qdrant 容器过滤测试：`.\mvnw.cmd -Pqdrant-integration verify`，需要 Docker 和 `qdrant/qdrant:v1.13.6` 镜像；仅验证 Qdrant 原生过滤，不代替应用端到端验收。
- 在 Docker Engine 29 上，如果 Testcontainers 提示 Docker API 返回 400，可在上述 Maven 命令中添加 `'-Dapi.version=1.44'`；镜像需能从 Docker Hub 拉取，或预先准备到本地。
- 真实 DashScope/Qdrant 端到端验收需要独立测试数据和本地凭证，参见 `E2E_ACCEPTANCE.md`；不要把真实调用加入普通测试。

## 已知限制

尚未接入飞书和前端；外部模型、Qdrant 及异常恢复依赖实际环境验收。API Key 是轻量认证方案，当前系统是原型，不应直接处理生产数据。
