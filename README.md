# Loom Agent

Loom Agent 是一个基于 Java 17、Spring Boot 3 和 DeepSeek 的后端 Agent 骨架。当前版本先把单 Agent 的最薄链路做稳：通过一个 SSE 接口把用户消息发给 DeepSeek，并逐字返回模型输出。

开发原则：每一步都保持项目可运行、可演示。先做单 Agent，再逐步接入代码检索、工具调用和多 Agent 编排。当前已提供一个节点化 ReAct Agent Loop，可用只读工具分析项目代码，也可在人工确认后执行文件修改、测试命令和受限 Git 操作。

## 模块结构

- `Loom_Agent-api`：对外 DTO、统一响应对象。
- `Loom_Agent-trigger`：HTTP Controller、SSE 协议适配、请求参数兜底。
- `Loom_Agent-domain`：会话流式服务、模型调用端口、重试/超时/输出格式校验。
- `Loom_Agent-infrastructure`：DeepSeek 网关实现、文件工具和 MyBatis DAO。
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
| `LOOM_DATA_DIR` | SQLite、artifact 的本地数据目录，默认 `~/.loom-agent` |
| `HOST_WORKSPACE_ROOT` | 宿主机工作区根目录（Docker 模式需要改成你的实际路径） |
| `CONTAINER_WORKSPACE_ROOT` | 容器内工作区根目录，默认 `/workspace` |
| `CONTAINER_WORKSPACE_ROOTS` | 容器内允许的工作区白名单 |

如果本机已有其他项目占用端口，只改应用端口映射即可：

```bash
APP_HOST_PORT=9091
```

### 方式一：纯 Docker（推荐，全容器化）

App 和 Playwright 都在容器内运行，SQLite 数据保存在 `app-data` volume：

```bash
cd docs/dev-ops
docker-compose --env-file ../env/.env -f docker-compose.yml up -d
```

启动后 App 监听 `http://localhost:8091`，工作区通过 Docker volume 映射：宿主机 `HOST_WORKSPACE_ROOT` → 容器 `/workspace`。

**重要**：`.env` 中 `HOST_WORKSPACE_ROOT` 需要设为你的实际工作目录（例如 `/Users/yourname/Desktop`），这样 Docker 容器才能读写你的项目文件。`CONTAINER_WORKSPACE_ROOT` 和 `CONTAINER_WORKSPACE_ROOTS` 保持 `/workspace`（容器内路径）。

Agent 接口中 `workspace` 字段传相对路径即可，例如 `"workspace":"java/Loom_Agent"`（相对于 `/workspace`）。

### 方式二：开发模式（IDE 或命令行启动应用）

SQLite 是嵌入式数据库，不需要先启动基础设施：

```bash
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

节点化 ReAct 代码 Agent。只读工具自动执行；文件写入、测试命令、Git 暂存/提交会返回普通审批事件；可审批高危动作默认返回高危审批事件，真正不可审批的危险动作才会硬拦截。请求字段：

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
- `high_risk_approval_required`：文件删除、普通 Git push/checkout 等高危但可审批的操作，批准后才会真实执行。
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
- `delete_files`：删除最多 20 个明确的工作区文件或目录路径，需要高危审批；目录会展示清单后递归删除，符号链接只删除链接本身，通配符、工作区根目录和任何 `.git` 路径永久拒绝。
- `run_shell`：进程级沙箱执行允许的只读命令或 Maven 测试命令。
- `git_op`：支持受限 Git 操作；`status/diff/log` 自动放行，`init/add/commit` 普通审批，普通 `push/reset/clean/rebase/checkout` 高危审批。

## 健壮性

- 超时：`AI_CONNECT_TIMEOUT_MS`、`AI_FIRST_TOKEN_TIMEOUT_MS`、`AI_STREAM_TIMEOUT_MS`。
- 重试：默认最多 3 次，只在首 token 输出前重试；429、500、503 会重试，400、401、402、422 不重试。
- 输出校验：空输出会返回 `output_empty`；`responseFormat=JSON_OBJECT` 时会校验完整输出是否为合法 JSON。
- 异常兜底：鉴权失败、余额不足、限流、服务过载、内容过滤、输出截断和格式错误都会映射为 SSE `error` 事件。
- Agent 工具权限：`READ_ONLY` 自动放行，`WRITE_CONFIRM` 需要普通审批，`HIGH_RISK_CONFIRM` 默认需要高危审批，`HIGH_RISK_DENY` 直接拦截。
- 子 Agent：主 Agent 可通过内建虚拟工具 `spawn_agents` 派生 explorer/reviewer/editor 子 Agent；explorer/reviewer 强制只读并可并发，editor 默认只允许单个串行执行。
- 上下文恢复：模型上下文超限后依次执行一次 reactive compact、切换更大上下文模型、分块深度摘要；根 Agent 最终进入可恢复的 `WAITING_USER_INPUT`，子 Agent 则以 `CONTEXT_OVERFLOW` 返回主 Agent。
- Agent 沙箱：
  - 所有文件、命令和 Git 操作都限制在请求解析后的 workspace 下；shell 不使用系统 shell 展开，禁止管道、重定向、后台执行和危险命令。
  - 路径安全：所有已存在路径强制 `toRealPath()` 解析，祖先符号链接指向工作区外部一律拒绝；遍历时统一跳过 `.git`、`.idea`、`target`、`node_modules`。
  - 敏感文件保护：`.env`、`.env.*`（除 `.env.example/.sample/.template`）、`*.key`、`*.pem`、`*.p12`、`id_rsa`、`id_ed25519` 禁止读取、搜索、创建和覆盖；`delete_files` 允许删除但标记 `SECRET_LIKE` 并需高危确认。
  - UTF-8 字节限制：`fileMaxBytes` 以 UTF-8 编码后的真实字节数为准；`write_file` 和 `replace_in_file` 在审批预览和执行阶段均校验。
  - 自动创建父目录：`write_file create` 自动创建多级父目录，创建后重新校验真实路径防止并发符号链接逃逸。
  - 原子写入：写入使用 `ATOMIC_MOVE` + `REPLACE_EXISTING`，保留 POSIX 文件原权限；临时文件始终在 `finally` 清理。
  - 审批指纹：`write_file`、`replace_in_file` 和 `delete_files` 审批时生成内容指纹（SHA-256）；执行前重新计算，不一致返回 `approval_stale`。
  - Diff 上限：结构化 Diff 先剥离公共前缀/后缀，仅对变化区域计算 LCS；变化区域超过 2,000,000 个 LCS 单元时返回 `diff_too_large`。
  - 分页读取：`read_file` 使用 `BufferedReader` 顺序读取，输出尾部追加 `shownLines`、`totalLines`、`nextStartLine` 元数据。
- 多工作区：`loom.agent.workspace-root` 是默认工作区；`loom.agent.allowed-workspace-roots` 是可选择工作区的白名单。为空时默认只允许 `workspace-root`。

## 状态持久化

Agent 运行时状态（run、checkpoint、approval、trace、context artifact）通过 SQLite + 本地文件系统持久化，支持重启后恢复。

### 持久化模式

通过 `loom.agent.persistence.mode` 配置：

| 模式 | 行为 |
|------|------|
| `sqlite`（默认） | 使用 `${LOOM_DATA_DIR}/loom-agent.db` 和本地 artifact 文件 |
| `memory` | 所有状态保存在内存中，仅适用于测试和临时 demo |

环境变量：`AGENT_PERSISTENCE_MODE`、`LOOM_DATA_DIR`、`AGENT_CONTEXT_STORAGE_ROOT`。

### 存储内容

| 存储 | 保存内容 |
|------|---------|
| SQLite `agent_run` | Agent 运行的元数据、状态、token 用量、父子关系 |
| SQLite `agent_run_checkpoint` | 上下文快照和计划 JSON，按版本号管理 |
| SQLite `agent_pending_approval` | 待审批操作及其过期时间 |
| SQLite `agent_trace_event` | 每步 trace 事件，支持 replay |
| SQLite `agent_context_artifact` | Context artifact 元数据 |
| SQLite `agent_undo_snapshot` | 每次根 Agent 运行前后的 Git ghost snapshot 和撤销状态 |
| SQLite `agent_workspace_undo_lock` | 同一工作区的撤销快照互斥锁 |
| 本地文件 `AGENT_CONTEXT_STORAGE_ROOT` | Artifact blob 内容（`.txt` 文件，按 `rootRunId/artifactId` 组织） |

### 数据库迁移

应用启动时由 Flyway 自动创建或升级 SQLite schema，迁移脚本位于 `Loom_Agent-app/src/main/resources/db/migration`。

### 验证步骤

1. 启动应用，检查 Flyway 和 SQLite 初始化日志。
2. 跑一次带 trace 的 agent 请求：
   ```bash
   curl -N -H "Accept: text/event-stream" -H "Content-Type: application/json" \
     -X POST http://localhost:8091/api/v1/agent/code/ask/stream \
     -d '{"question":"分析项目结构","maxSteps":3,"includeTrace":true}'
   ```
3. 重启应用后验证：
   - `GET /api/v1/agent/code/approvals/{approvalId}` — 审批记录仍可查
   - `GET /api/v1/agent/code/runs/{runId}/replay` — trace timeline 仍可查
   - context artifact 通过 `context_recall` 工具可读回

更多设计细节见 [backend-architecture.md](docs/design/backend-architecture.md)。
Agent Loop 设计见 [agent-loop.md](docs/design/agent-loop.md)。
