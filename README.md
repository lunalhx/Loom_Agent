# Loom Agent

Loom Agent 是一个基于 Java 17、Spring Boot 3 和 DeepSeek 的后端 Agent 骨架。当前版本先把单 Agent 的最薄链路做稳：通过一个 SSE 接口把用户消息发给 DeepSeek，并逐字返回模型输出。

开发原则：每一步都保持项目可运行、可演示。先做单 Agent，再逐步接入代码检索、工具调用和多 Agent 编排。当前已提供一个节点化 ReAct Agent Loop，可用只读工具分析项目代码，也可在人工确认后执行文件修改、测试命令和受限 Git 操作。

## 模块结构

- `Loom_Agent-api`：对外 DTO、统一响应对象。
- `Loom_Agent-trigger`：HTTP Controller、SSE 协议适配、请求参数兜底。
- `Loom_Agent-domain`：会话流式服务、模型调用端口、重试/超时/输出格式校验。
- `Loom_Agent-infrastructure`：DeepSeek 网关实现、只读文件工具、MyBatis DAO、Redis 缓存占位。
- `Loom_Agent-app`：Spring Boot 启动、配置绑定、线程池、MyBatis 扫描。
- `Loom_Agent-types`：通用异常、响应码、常量。

## 本地运行

提供两种启动方式，**选其中一种即可**，不需要同时启动。

### 前置：准备环境变量

```bash
cp docs/env/.env.example docs/env/.env
```

然后把 `docs/env/.env` 中的 `DEEPSEEK_API_KEY` 改成自己的 DeepSeek API Key。真实 `.env` 已加入 `.gitignore`，提交时只提交 `.env.example`。

`.env` 关键配置项：

| 变量 | 说明 |
|------|------|
| `SERVER_PORT` / `APP_HOST_PORT` | 应用端口，默认 8091 |
| `MYSQL_HOST_PORT` | 宿主机 MySQL 端口，默认 13306 |
| `REDIS_HOST_PORT` | 宿主机 Redis 端口，默认 16379 |
| `HOST_WORKSPACE_ROOT` | 宿主机工作区根目录（Docker 模式需要改成你的实际路径） |
| `CONTAINER_WORKSPACE_ROOT` | 容器内工作区根目录，默认 `/workspace` |
| `CONTAINER_WORKSPACE_ROOTS` | 容器内允许的工作区白名单 |

如果本机已有其他项目占用端口，只改宿主机端口映射即可：

```bash
APP_HOST_PORT=9091
MYSQL_HOST_PORT=23306
REDIS_HOST_PORT=26379
```

### 方式一：纯 Docker（推荐，全容器化）

所有服务（MySQL + Redis + App）都在容器内运行。一条命令启动：

```bash
cd docs/dev-ops
docker-compose --env-file ../env/.env -f docker-compose.yml up -d
```

启动后 App 监听 `http://localhost:8091`，工作区通过 Docker volume 映射：宿主机 `HOST_WORKSPACE_ROOT` → 容器 `/workspace`。

**重要**：`.env` 中 `HOST_WORKSPACE_ROOT` 需要设为你的实际工作目录（例如 `/Users/yourname/Desktop`），这样 Docker 容器才能读写你的项目文件。`CONTAINER_WORKSPACE_ROOT` 和 `CONTAINER_WORKSPACE_ROOTS` 保持 `/workspace`（容器内路径）。

Agent 接口中 `workspace` 字段传相对路径即可，例如 `"workspace":"java/Loom_Agent"`（相对于 `/workspace`）。

### 方式二：开发模式（Docker 基础设施 + IDE 启动应用）

Docker 只启动 MySQL 和 Redis，应用由 IDEA 或命令行启动：

```bash
# 1. 启动基础设施
cd docs/dev-ops
docker-compose --env-file ../env/.env -f docker-compose-environment.yml up -d

# 2. 启动 Spring Boot 应用（dev profile 自动连 127.0.0.1:13306/16379）
mvn -pl Loom_Agent-app -am spring-boot:run
```

App 以 dev profile 运行，直接访问本地文件系统，`workspace` 可以传绝对路径（如 `/Users/yourname/Desktop/java/Loom_Agent`）或相对路径。

### 验证

```bash
# 检查模型配置
curl http://localhost:8091/api/v1/model/config

# 调用流式接口
curl -N \
  -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8091/api/v1/chat/stream \
  -d '{"message":"用一句话介绍你自己"}'

# 调用代码分析 Agent
curl -N \
  -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8091/api/v1/agent/code/ask/stream \
  -d '{"question":"DefaultChatStreamService.stream 在哪里定义？做什么用？","maxSteps":6,"includeTrace":true}'
```

## 接口

`POST /api/v1/chat/stream`

请求字段：

- `message`：必填，用户消息。
- `conversationId`：可选，会话 ID。
- `systemPrompt`：可选，系统提示词。
- `model`：可选，仅支持 `deepseek-v4-flash`、`deepseek-v4-pro`。
- `temperature`：可选，0 到 2。
- `maxTokens`：可选，最大输出 token。
- `responseFormat`：可选，`TEXT` 或 `JSON_OBJECT`。

SSE 事件：

- `meta`：返回 `requestId`、`conversationId`、`model`。
- `token`：逐个 Unicode 字符返回模型内容。
- `done`：正常结束，包含 `finishReason` 和 token usage。
- `error`：兜底错误，包含 `code` 和可展示 `message`。

`GET /api/v1/model/config`

返回当前生效的模型配置，API Key 会脱敏。

`POST /api/v1/agent/code/ask/stream`

节点化 ReAct 代码 Agent。只读工具自动执行；文件写入、测试命令、Git 暂存/提交会返回审批事件；高危动作默认拦截。请求字段：

- `question`：代码任务；也兼容 `message` 字段，二者至少填一个。
- `workspace`：可选工作区选择。为空时使用默认 `loom.agent.workspace-root`；相对路径会基于 `loom.agent.allowed-workspace-roots` 解析；绝对路径也必须落在白名单根目录下。
- `maxSteps`：可选，最大工具调用步数。
- `includeTrace`：可选，是否返回 `thought/tool_call/observation` 等中间事件。

示例：

```bash
curl -N \
  -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8091/api/v1/agent/code/ask/stream \
  -d '{"message":"帮我分析这个项目","workspace":"Agentic_RAG","maxSteps":6,"includeTrace":true}'
```

也可以传白名单下的绝对路径：

```json
{"message":"帮我分析这个项目","workspace":"/Users/lunalhx/Desktop/java/Agentic_RAG"}
```

非法 workspace 会直接返回 `error`，不会回退默认工作区。常见错误包括 `WORKSPACE_NOT_FOUND`、`WORKSPACE_NOT_DIRECTORY`、`WORKSPACE_NOT_ALLOWED`、`WORKSPACE_PATH_ESCAPE`。

Agent SSE 事件：

- `meta`：返回请求元信息。
- `node_start`：节点开始执行，包含 `node` 和 `nodeInputs`，`includeTrace=true` 时返回。
- `thought`：下一步行动意图摘要。
- `tool_call`：工具名和参数。
- `context_compacted`：上下文已执行预压缩、reactive compact 或深度摘要，metadata 包含压缩前后 token 估算、目标大小、策略和 transcript artifact ID。
- `approval_required`：写操作等待人工确认，包含 `approvalId`、`permissionLevel`、`riskReason`、`operationPreview`、`expiresAt`。
- `user_input_required`：自动上下文恢复已耗尽，运行进入 `WAITING_USER_INPUT`；可提交更聚焦的指令继续，或终止运行。
- `policy_denied`：高危动作被拦截，不会执行真实操作。
- `sub_agent_started`：子 Agent 子任务开始，包含 `subAgentTaskId`、`subAgentRunId`、`subAgentRole`。
- `sub_agent_completed`：子 Agent 成功完成，只返回轻量元信息。
- `sub_agent_failed`：子 Agent 失败或超时，其他子 Agent 会继续汇总。
- `sub_agent_summary`：所有子 Agent 的聚合摘要，不包含子 Agent 中间工具日志。
- `observation`：工具观察结果。
- `answer`：最终回答。
- `done`：结束原因和步数。
- `error`：兜底错误。

`GET /api/v1/agent/code/approvals/{approvalId}`

查询待审批操作，返回审批状态、工具名、参数摘要、风险原因和过期时间。

`POST /api/v1/agent/code/approvals/{approvalId}/decide/stream`

审批并继续 Agent Loop：

```bash
curl -N \
  -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8091/api/v1/agent/code/approvals/{approvalId}/decide/stream \
  -d '{"decision":"APPROVE","reason":"允许本次文件修改"}'
```

`decision` 只能是 `APPROVE` 或 `REJECT`。批准后从暂停的工具调用继续执行；拒绝后会把 `approval_rejected` 作为工具观察结果反馈给模型。

`POST /api/v1/agent/code/runs/{runId}/input/stream`

上下文恢复等待阶段补充用户输入：

```bash
curl -N \
  -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8091/api/v1/agent/code/runs/{runId}/input/stream \
  -d '{"action":"CONTINUE","message":"只处理当前模块，忽略其余历史分支"}'
```

`action` 支持 `CONTINUE` 和 `ABORT`。`CONTINUE` 必须提供 1–4000 字符的 `message`，并从压缩后的 checkpoint 继续；`ABORT` 以 `CONTEXT_OVERFLOW` 结束。

写类工具：

- `replace_in_file`：按精确文本替换文件内容，要求匹配次数符合 `expectedOccurrences`。
- `write_file`：创建或覆盖工作区内文本文件。
- `run_shell`：进程级沙箱执行允许的只读命令或 Maven 测试命令。
- `git_op`：支持 `status/diff/log/add/commit`，其中 `add/commit` 需要审批。

## 健壮性

- 超时：`AI_CONNECT_TIMEOUT_MS`、`AI_FIRST_TOKEN_TIMEOUT_MS`、`AI_STREAM_TIMEOUT_MS`。
- 重试：默认最多 3 次，只在首 token 输出前重试；429、500、503 会重试，400、401、402、422 不重试。
- 输出校验：空输出会返回 `output_empty`；`responseFormat=JSON_OBJECT` 时会校验完整输出是否为合法 JSON。
- 异常兜底：鉴权失败、余额不足、限流、服务过载、内容过滤、输出截断和格式错误都会映射为 SSE `error` 事件。
- Agent 工具权限：`READ_ONLY` 自动放行，`WRITE_CONFIRM` 需要 HITL 审批，`HIGH_RISK_DENY` 直接拦截。
- 子 Agent：主 Agent 可通过内建虚拟工具 `spawn_agents` 派生 explorer/reviewer/editor 子 Agent；explorer/reviewer 强制只读并可并发，editor 默认只允许单个串行执行。
- 上下文恢复：模型上下文超限后依次执行一次 reactive compact、切换更大上下文模型、分块深度摘要；根 Agent 最终进入可恢复的 `WAITING_USER_INPUT`，子 Agent 则以 `CONTEXT_OVERFLOW` 返回主 Agent。
- Agent 沙箱：所有文件、命令和 Git 操作都限制在请求解析后的 workspace 下；shell 不使用系统 shell 展开，禁止管道、重定向、后台执行和危险命令。
- 多工作区：`loom.agent.workspace-root` 是默认工作区；`loom.agent.allowed-workspace-roots` 是可选择工作区的白名单。为空时默认只允许 `workspace-root`。

更多设计细节见 [backend-architecture.md](docs/design/backend-architecture.md)。
Agent Loop 设计见 [agent-loop.md](docs/design/agent-loop.md)。
