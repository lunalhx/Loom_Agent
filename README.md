# Loom Agent

基于 **Java 21**、**Spring Boot 3.4**、**Spring AI** 和 **DeepSeek** 的后端 AI Coding Agent。

Loom Agent 是一个生产级自主编程助手，能够理解自然语言编码请求，在沙箱工作区内读取/搜索/编辑文件、执行命令、操作 Git、调用 MCP 工具，并通过层级子 Agent 分解复杂任务。

## 目录

- [核心特性](#核心特性)
- [快速开始](#快速开始)
  - [环境准备](#环境准备)
  - [方式一：Docker 部署（推荐）](#方式一docker-部署推荐)
  - [方式二：本地开发模式](#方式二本地开发模式)
  - [验证](#验证)
- [API 接口](#api-接口)
  - [Agent 执行](#agent-执行)
  - [Agent 运行管理](#agent-运行管理)
  - [审批与恢复](#审批与恢复)
  - [工作区与会话](#工作区与会话)
  - [后台任务](#后台任务)
  - [记忆与技能](#记忆与技能)
- [Agent 工具](#agent-工具)
- [架构总览](#架构总览)
- [配置参考](#配置参考)
- [高级特性](#高级特性)
- [常见问题](#常见问题)

---

## 核心特性

| 类别 | 特性 |
|------|------|
| 🤖 **Agent 执行** | 节点化 ReAct 循环，自动规划 → 执行 → 观察 → 反思，支持 16 个流程节点 |
| 🔧 **工具系统** | 17 个内置工具（文件读写、代码搜索、Shell 沙箱、Git 操作、结构化 Diff 等） |
| 🔌 **MCP 集成** | 支持 Model Context Protocol，可扩展 Playwright 浏览器自动化、Exa 网络搜索等外部工具 |
| 👥 **层级子 Agent** | 主 Agent 可派生 explorer / reviewer / editor 子 Agent，支持并发与串行控制 |
| 🧠 **长期记忆** | 基于 SQLite-vec 的向量搜索记忆系统，自动提取与检索上下文相关记忆 |
| ✅ **审批门控** | 多级权限模型（READ_ONLY / WRITE_CONFIRM / HIGH_RISK_CONFIRM / DENY），高危操作需人工确认 |
| 🔄 **撤销能力** | 每次 Agent 运行前后生成 Git ghost snapshot，支持一键回滚 |
| 📡 **流式通信** | 基于 SSE 的实时事件流，支持 thought / tool_call / observation / answer 等完整追踪 |
| 🛡️ **上下文管理** | 自动 Token 预算、reactive compact、深度摘要、上下文溢出可恢复暂停 |
| 🔁 **模型韧性** | Resilience4j 重试 + 熔断 + 回退模型切换，首 Token 超时保护 |
| 📊 **可观测性** | Micrometer + Prometheus 指标、结构化日志、Prompt 缓存诊断 |
| 💾 **状态持久化** | SQLite 持久化 Agent 运行状态、审批、trace、artifact，重启可恢复 |
| 🐳 **容器化部署** | 多阶段 Docker 构建 + Docker Compose，开箱即用 |
| ⚙️ **技能系统** | 文件系统技能仓库，支持加载自定义 Agent 行为指令 |

---

## 快速开始

### 环境准备

- **JDK 21+**（本地开发模式）
- **Docker & Docker Compose**（容器化部署模式）
- **DeepSeek API Key**（[获取地址](https://platform.deepseek.com/api_keys)）
- （可选）**DashScope API Key**（用于 Embedding 向量检索，[获取地址](https://dashscope.aliyun.com)）

### 方式一：Docker 部署（推荐）

App 和 Playwright 全部容器化运行，SQLite 数据持久化在 `app-data` volume：

```bash
# 1. 配置环境变量
cp docs/env/.env.example docs/env/.env
# 编辑 docs/env/.env，填入 DEEPSEEK_API_KEY（或切换 OPENCODE_GO_API_KEY + LOOM_AI_PROVIDER=opencode-go），并修改 HOST_WORKSPACE_ROOT 为实际路径

# 2. 启动
cd docs/dev-ops
docker-compose --env-file ../env/.env -f docker-compose.yml up -d

# 3. 查看日志
docker-compose -f docker-compose.yml logs -f
```

App 默认监听 `http://localhost:8091`。工作区通过 volume 映射：`宿主机 HOST_WORKSPACE_ROOT → 容器 /workspace`。

> 💡 **提示**：`.env` 中 `HOST_WORKSPACE_ROOT` 必须设为你的实际工作目录（如 `/Users/yourname/Desktop`）。Agent 接口中 `workspace` 传相对路径（相对 `/workspace`）即可，如 `"workspace":"java/Loom_Agent"`。

### 方式二：本地开发模式

SQLite 是嵌入式数据库，无需额外基础设施：

```bash
# 1. 配置环境变量
cp docs/env/.env.example docs/env/.env
# 编辑 docs/env/.env，填入 DEEPSEEK_API_KEY（或切换 OPENCODE_GO_API_KEY + LOOM_AI_PROVIDER=opencode-go）

# 2. 启动应用
mvn -pl Loom_Agent-app -am spring-boot:run
```

App 以 dev profile 运行，直接访问本地文件系统，`workspace` 可传绝对路径。

### 验证

```bash
# 检查模型配置
curl http://localhost:8091/api/v1/agent/code/model/config

# 分析项目结构
curl -N \
  -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8091/api/v1/agent/code/ask/stream \
  -d '{"question":"分析项目结构","maxSteps":6,"includeTrace":true}'
```

---

## API 接口

所有接口前缀：`/api/v1/agent/code`

### Agent 执行

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/ask/stream` | 发送编码任务，SSE 流式返回 |

请求体：

```json
{
  "question": "帮我分析这个项目的架构",
  "workspace": "java/Loom_Agent",
  "maxSteps": 10,
  "includeTrace": true
}
```

> `question` 与 `message` 至少填一个；`workspace` 为空时使用默认工作区，相对路径基于白名单解析，绝对路径必须落在白名单内。

### Agent 运行管理

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/run/{runId}/status` | 获取运行状态 |
| `GET` | `/run/{runId}/trace` | 获取追踪时间线 |
| `GET` | `/run/{runId}/usage` | 获取 Token 用量 |
| `POST` | `/run/{runId}/replay/stream` | 回放 Agent 运行 |
| `GET` | `/runtime` | 当前运行时信息 |
| `POST` | `/undo` | 执行工作区撤销 |
| `GET` | `/undo/status` | 查询撤销状态 |

### 审批与恢复

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/approval` | 提交审批决策（APPROVE / REJECT） |
| `POST` | `/run/{runId}/input/stream` | 上下文溢出后补充用户输入继续运行 |

审批示例：

```bash
curl -N \
  -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8091/api/v1/agent/code/approval \
  -d '{"decision":"APPROVE","reason":"允许本次文件修改"}'
```

上下文恢复示例：

```bash
curl -N \
  -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8091/api/v1/agent/code/run/{runId}/input/stream \
  -d '{"action":"CONTINUE","message":"只处理当前模块"}'
```

### 工作区与会话

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/workspace/tree` | 工作区文件树 |
| `GET` | `/conversation/{convId}/summary` | 会话摘要 |
| `DELETE` | `/conversation/{convId}` | 删除会话数据 |

### 后台任务

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/background-task/{taskId}` | 查询后台 Shell 任务状态 |
| `POST` | `/background-task/{taskId}/cancel` | 取消后台任务 |
| `GET` | `/background-task/{taskId}/log` | 读取后台任务日志 |

### 记忆与技能

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/memory` | 获取 Agent 长期记忆 |
| `POST` | `/skill/query` | 查询可用技能 |

### SSE 事件类型

| 事件 | 说明 |
|------|------|
| `meta` | 请求元信息（runId、workspace 等） |
| `node_start` | 节点开始执行（`includeTrace=true` 时返回） |
| `thought` | 下一步行动意图摘要 |
| `tool_call` | 工具名和参数 |
| `observation` | 工具执行的观察结果 |
| `answer` | 最终回答 |
| `done` | 结束原因和总步数 |
| `error` | 异常兜底 |
| `approval_required` | 写操作等待人工确认 |
| `high_risk_approval_required` | 高危但可审批的操作 |
| `user_input_required` | 上下文溢出，等待用户输入 |
| `policy_denied` | 高危动作被硬拦截 |
| `sub_agent_started` | 子 Agent 子任务开始 |
| `sub_agent_completed` | 子 Agent 成功完成 |
| `sub_agent_failed` | 子 Agent 失败或超时 |
| `sub_agent_summary` | 所有子 Agent 聚合摘要 |
| `context_compacted` | 上下文已压缩 |

---

## Agent 工具

Agent 可调用以下内置工具，所有操作限制在解析后的 workspace 内：

| 工具 | 权限级别 | 说明 |
|------|----------|------|
| `read_file` | READ_ONLY | 分页读取文件，自动报告行号范围 |
| `write_file` | WRITE_CONFIRM | 创建或覆盖文本文件，原子写入 + SHA-256 审批指纹 |
| `replace_in_file` | WRITE_CONFIRM | 精确文本替换，支持 expectedOccurrences 匹配 |
| `delete_files` | HIGH_RISK_CONFIRM | 删除文件/目录，最多 20 个路径，禁止 `.git` |
| `find_files` | READ_ONLY | Glob 模式文件搜索 |
| `list_directory` | READ_ONLY | 列出目录内容 |
| `code_search` | READ_ONLY | 正则表达式内容搜索 |
| `run_shell` | 按命令分类 | 进程级沙箱执行命令，禁止管道/重定向/后台 |
| `shell_task` | WRITE_CONFIRM | 长时间后台 Shell 任务，支持状态监控和日志读取 |
| `git_op` | 按操作分类 | Git 操作：status/diff/log 自动放行，commit/add 需审批，push/reset/rebase 需高危审批 |
| `memory_search` | READ_ONLY | 向量搜索长期记忆 |
| `memory_save` | WRITE_CONFIRM | 保存记忆到向量索引 |
| `todo_write` | WRITE_CONFIRM | 写入任务清单 |
| `skill_query` | READ_ONLY | 查询技能描述 |
| `spawn_agents` | 虚拟工具 | 派生 explorer / reviewer / editor 子 Agent |

此外，通过 MCP 可扩展：
- **Playwright**：浏览器导航、点击、输入、截图
- **Exa**：网络搜索与内容抓取

### 工作区沙箱

- 所有文件/命令/Git 操作限制在解析后的 workspace 根目录
- Shell 不使用系统 Shell 展开；禁止管道、重定向、后台执行和危险命令
- 路径解析使用 `toRealPath()` 防符号链接逃逸
- 自动跳过 `.git`、`.idea`、`target`、`node_modules` 遍历
- 敏感文件（`.env`、`*.key`、`*.pem`、私钥等）禁止读取和搜索

---

## 架构总览

### 模块结构（DDD 六边形架构）

```
Loom_Agent-app           ← Spring Boot 启动、配置装配、线程池
├── Loom_Agent-trigger    ← HTTP Controller、SSE 协议适配、请求映射
├── Loom_Agent-domain     ← 领域核心：Agent 循环、工具模型、记忆、审批
│   └── Loom_Agent-types  ← 共享内核：异常、响应码、常量
└── Loom_Agent-infrastructure ← 适配器：DeepSeek 网关、工具实现、MyBatis DAO、MCP
    └── Loom_Agent-domain
```

### Agent 执行流程（16 个节点）

```
SkillBootstrap → Start → Plan → RenderPrompt → ModelCall → Decision
    → [InstructionGate] → [ApprovalGate] → ToolDispatch / SubAgentDispatch
    → Observation → [ReplanGuard → Replan] → FinalAnswer
    → [UserInputGate]
    → Fail (兜底)
```

### 技术栈

| 层级 | 技术 |
|------|------|
| **语言** | Java 21 |
| **框架** | Spring Boot 3.4.3 |
| **AI** | Spring AI 1.0.9 + DeepSeek / OpenCode Go |
| **MCP** | Model Context Protocol SDK 1.1.1 |
| **数据库** | SQLite + sqlite-vec 向量扩展 + Flyway 迁移 |
| **ORM** | MyBatis 3.0.4 |
| **韧性** | Resilience4j 2.2.0（重试 / 熔断 / 回退） |
| **向量** | DashScope / OpenAI 兼容 Embedding API |
| **可观测性** | Micrometer + Prometheus |
| **构建** | Maven 3.9+ |
| **部署** | Docker 多阶段构建 + Docker Compose |

---

## 配置参考

### 核心环境变量

`.env` 关键配置项（详见 `docs/env/.env.example`）：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `LOOM_AI_PROVIDER` | `deepseek` | AI 提供商：`deepseek` / `opencode-go` |
| `DEEPSEEK_API_KEY` | （deepseek 必填） | DeepSeek API Key |
| `OPENCODE_GO_API_KEY` | （opencode-go 必填） | OpenCode Go API Key |
| `DASHSCOPE_API_KEY` | （可选） | Embedding 向量 API Key |
| `SERVER_PORT` / `APP_HOST_PORT` | `8091` | 应用端口 |
| `LOOM_DATA_DIR` | `~/.loom-agent` | SQLite、artifact 本地数据目录 |
| `AGENT_PERSISTENCE_MODE` | `sqlite` | 持久化模式：`sqlite` / `memory` |
| `HOST_WORKSPACE_ROOT` | — | Docker 模式宿主机工作区目录 |
| `CONTAINER_WORKSPACE_ROOT` | `/workspace` | 容器内工作区根目录 |

### Spring Profiles

| Profile | 用途 |
|---------|------|
| `dev` | 开发模式，默认启用 |
| `test` | 测试模式 |
| `prod` | 生产模式 |

通过 `SPRING_PROFILES_ACTIVE` 环境变量切换。

---

## 高级特性

### 层级子 Agent

主 Agent 可通过 `spawn_agents` 虚拟工具派生子 Agent：

- **explorer**：只读代码库探索和搜索，可并发执行
- **reviewer**：代码审查和架构评估，强制只读
- **editor**：文件编辑和代码修改，默认单实例串行

子 Agent 拥有独立的工具注册表、Token 预算和超时限制。主 Agent 自动汇总各子 Agent 结果。

### 上下文管理

模型上下文超出限制后的三级应对策略：

1. **Reactive Compact** — 压缩历史消息，保留关键信息
2. **切换更大上下文模型** — 自动切换到支持更大上下文的模型
3. **分块深度摘要** — 对历史进行分段摘要

根 Agent 最终进入 `WAITING_USER_INPUT` 可恢复暂停；子 Agent 以 `CONTEXT_OVERFLOW` 返回主 Agent。

### 撤销机制

每次根 Agent 运行前后自动创建 Git ghost snapshot：
- 成功时 snapshot 保留用于可选回滚
- 失败时可一键撤销到运行前状态
- 同一工作区串行加锁，防止并发冲突

### 审批系统

多级权限模型：

| 级别 | 行为 |
|------|------|
| `READ_ONLY` | 自动放行 |
| `WRITE_CONFIRM` | 暂停等待用户审批（含内容 SHA-256 指纹） |
| `HIGH_RISK_CONFIRM` | 高危审批，需明确确认 |
| `HIGH_RISK_DENY` | 直接拦截，不可审批 |

审批指纹防篡改：审批时生成 SHA-256 内容指纹，执行前重新校验，不一致返回 `approval_stale`。

### 模型韧性

- 首 Token 超时保护（`AI_FIRST_TOKEN_TIMEOUT_MS`）
- 智能重试：仅首 Token 前重试，429/500/503 重试，400/401/402/422 不重试
- 熔断：连续失败触发断路器
- 回退：故障时自动切换到备用模型

### 状态持久化

| 数据 | 存储 | 说明 |
|------|------|------|
| Agent Run 状态 | SQLite | 运行元数据、状态、Token 用量 |
| Checkpoint | SQLite | 上下文快照，按版本管理 |
| 审批记录 | SQLite | 待审批操作及过期时间 |
| Trace 事件 | SQLite | 每步 trace，支持回放 |
| Context Artifact | SQLite + 本地文件 | 上下文产物元数据与 blob |
| Undo Snapshot | SQLite + Git | 撤销快照元数据 |
| 长期记忆 | SQLite-vec | 向量索引记忆 |

### 提示缓存诊断

Prompt 缓存自动诊断系统，追踪每次模型调用的缓存命中/缺失、规范化消息哈希、以及跨调用的缓存效果对比，辅助优化上下文构建策略。

---

## 常见问题

### 端口被占用

修改 `.env` 中的 `APP_HOST_PORT` 即可：

```bash
APP_HOST_PORT=9091
```

`SERVER_PORT` 保持 `8091`（容器内部端口），仅改映射端口。

### workspace 路径错误

常见错误码及原因：

| 错误码 | 原因 |
|--------|------|
| `WORKSPACE_NOT_FOUND` | 路径不存在 |
| `WORKSPACE_NOT_DIRECTORY` | 路径不是目录 |
| `WORKSPACE_NOT_ALLOWED` | 路径不在白名单内 |
| `WORKSPACE_PATH_ESCAPE` | 路径尝试逃逸工作区 |

Docker 模式下传相对路径（相对 `/workspace`），本地开发模式可传绝对路径（需在白名单内）。

### 模型调用超时

检查 DeepSeek API Key 是否有效，网络是否可达。超时时间可通过环境变量调整：

- `AI_CONNECT_TIMEOUT_MS`
- `AI_FIRST_TOKEN_TIMEOUT_MS`
- `AI_STREAM_TIMEOUT_MS`

### 数据库初始化

首次启动时 Flyway 自动创建 SQLite 数据库和表结构。迁移脚本位于 `Loom_Agent-app/src/main/resources/db/migration`。

---

## 相关文档

- [架构设计](docs/design/backend-architecture.md)
- [Agent 循环设计](docs/design/agent-loop.md)

## 许可证

[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
