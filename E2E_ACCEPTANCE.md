# 真实端到端验收说明

当前仓库已准备 `scripts/e2e-smoke.ps1`。脚本复用 `application.yml` 中的默认 MySQL 主机/端口、Redis `localhost:6379` 和 Qdrant 本机地址；真实验收使用独立数据库 `agile_agent_e2e_20261121`，不使用现有业务库。

## 环境边界

必须额外指定独立测试库名，例如 `agile_agent_e2e_20261121`，不能复用 `application.yml` 中的 `agile_agent_db`。先启动：

- MySQL：隔离测试数据库，主机/端口默认复用 `application.yml` 的 `localhost:3306`；
- Redis：默认复用 `application.yml` 的 `localhost:6379`；
- Qdrant：默认复用本机配置，HTTP 健康检查 `6333`，gRPC 服务 `6334`；
- Spring Boot：使用同一隔离 MySQL、Redis 和 Qdrant 配置；
- DashScope：通过 `DASHSCOPE_API_KEY` 注入，不写入文件。

PowerShell 示例：

```powershell
$env:DB_PASSWORD = '<隔离 MySQL 密码>'
$env:DASHSCOPE_API_KEY = '<DashScope Key>'
$env:E2E_MYSQL_DATABASE = 'agile_agent_e2e_20261121'
# 以下地址与 application.yml 默认值一致；只有服务不在本机时才需要覆盖
# $env:E2E_MYSQL_HOST = 'localhost'
# $env:E2E_MYSQL_PORT = '3306'
$env:E2E_MYSQL_USER = 'root'
# $env:E2E_REDIS_HOST = 'localhost'
# $env:E2E_REDIS_PORT = '6379'
# $env:E2E_QDRANT_URL = 'http://localhost:6333'
$env:E2E_BASE_URL = 'http://localhost:8080'
```

应用启动前把 JDBC URL 指向隔离库，例如：

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://localhost:3306/agile_agent_e2e_20261121?useUnicode=true&characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true'
.\mvnw.cmd spring-boot:run
```

Flyway 会在隔离库执行 V1-V4；不要在现有 `agile_agent_db` 上执行本脚本。

## 自动验收

主流程脚本会创建测试用户并临时取得 Key；只有单独运行真实 SSE 集成测试时，才需要设置测试 API Key：

```powershell
$env:AGILE_REAL_STREAM_TEST = 'true'
$env:AGILE_TEST_API_KEY = '<测试用户 API Key>'
$env:AGILE_TEST_PROJECT_ID = '<隔离项目 ID>'
$env:AGILE_TEST_REQUEST_ID = 'stream-e2e-001'
$env:AGILE_TEST_TASK_MARKER = '<隔离项目中的已知任务标题>'
$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://localhost:3306/agile_agent_e2e_20261121?useUnicode=true&characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true'
$env:SPRING_DATASOURCE_DRIVER_CLASS_NAME = 'com.mysql.cj.jdbc.Driver'
$env:SPRING_DATASOURCE_USERNAME = 'root'
$env:SPRING_DATASOURCE_PASSWORD = $env:DB_PASSWORD
$env:SPRING_FLYWAY_ENABLED = 'false'
```

执行主流程脚本：

```powershell
.\scripts\e2e-smoke.ps1
```

执行真实 SSE + `queryTasks` 集成测试：

```powershell
.\mvnw.cmd "-Dtest=RealStreamToolContextIntegrationTest" test
```

脚本会生成脱敏报告到 `e2e-artifacts/`，不会记录完整 API Key、Authorization Header 或会议正文。

## 覆盖范围

自动脚本覆盖：

1. MySQL、Redis、Qdrant 和 Spring Boot 连通性；
2. 测试用户、项目和 Viewer 成员；
3. 正常会议索引、草稿生成、编辑、删除、确认、提交和任务查询；
4. 相同幂等键重复提交；
5. 模糊会议和无任务会议；
6. Viewer 写入拒绝；
7. 审计日志数量查询；
8. `searchKnowledge` 历史检索；
9. 可选真实 SSE `queryTasks`，检查真实任务、`task_issue` SQL、Tool 上下文和 requestId。

需要人工执行且脚本不会主动破坏服务：

- 撤销 Key 后请求必须返回 `401`；
- 过期 Key 请求必须返回 `401`；
- 非成员、跨项目草稿/任务请求必须返回 `403` 或安全的 `404`；
- 临时停止 Qdrant、数据库提交失败、模型调用失败后恢复服务，确认错误码、requestId 和无敏感信息；
- 连接 SSE 后主动断开客户端，确认服务端清理；
- 等待 SSE 超时，确认不会遗留异常上下文或错误草稿状态；
- 检查 Redis 中 `chat:memory:<userId>:<projectId>:v2` 和 `chat:memory:manual:<userId>:<projectId>:v2` 不跨用户/项目串写。

## 当前结果

- 普通 `.\mvnw.cmd test`：已通过本地测试；不依赖真实 MySQL、Redis、Qdrant 或 DashScope。
- 真实主流程：已通过隔离 MySQL、Redis、Qdrant 和 DashScope 验收，报告见 `e2e-artifacts/e2e-report-20261121-211225.md`。
- 真实 SSE `queryTasks`：已通过；验证返回真实任务、`task_issue` SQL、Tool 线程 ProjectScope/requestId 恢复，未出现“缺少当前用户上下文”。
- 仍待人工：停止服务后的故障注入、SSE 客户端断开/超时，以及 Redis 隔离检查（本机没有 `redis-cli`）。
- 是否进入 RAG metadata filter、去重和来源引用阶段：基础真实链路已通过，可以单独评估下一阶段；本阶段未实现这些结构性功能。
