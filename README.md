# Loom Agent

基于 **Java 21** 的本地编码 Agent，是 [Python loom-code](https://github.com/) 的 Java 移植。采用 Loom XML 工具协议，同步 CLI/REPL，工具调用统一由 `ToolExecutor` 治理，会话与运行轨迹保存在工作区 `.loom-code` 目录。

## 目录

- [核心特性](#核心特性)
- [快速开始](#快速开始)
  - [一次性执行](#一次性执行)
  - [交互式 REPL](#交互式-repl)
- [命令行参数](#命令行参数)
- [工具系统](#工具系统)
- [MCP 外部工具](#mcp-外部工具)
- [审批策略](#审批策略)
- [会话与存储](#会话与存储)
- [架构总览](#架构总览)
- [Provider 配置](#provider-配置)
- [常见问题](#常见问题)

---

## 核心特性

| 类别 | 特性 |
|------|------|
| 🤖 **Agent 循环** | Prompt → Model → Loom XML Parser → ToolExecutor 的同步控制循环 |
| 🔧 **工具系统** | 六个基础工具 + 受深度控制的 `delegate`，全部经过 `ToolExecutor` 治理 |
| 🛡️ **安全治理** | allowedTools 白名单、参数语义校验、连续重复调用检测、审批策略、工作区快照 diff |
| 📝 **XML 协议** | `<tool>{...json...}</tool>`、`<tool name=...>...</tool>`、`<final>...</final>` |
| 💾 **文件存储** | 会话/运行轨迹/report 保存在 `.loom-code/sessions` 与 `.loom-code/runs` |
| 🔌 **四类 Provider** | DeepSeek、OpenAI-compatible、Anthropic-compatible、Ollama |
| 🖥️ **CLI/REPL** | 一次性执行或交互式会话，无 HTTP、无端口监听 |

---

## 快速开始

### 构建

```bash
mvn clean package -DskipTests
```

产物：`Loom_Agent-app/target/Loom_Agent-app.jar`

### 一次性执行

```bash
java -jar Loom_Agent-app/target/Loom_Agent-app.jar --cwd /path/to/repo "读取 README 并总结项目结构"
```

### 交互式 REPL

```bash
java -jar Loom_Agent-app/target/Loom_Agent-app.jar --cwd /path/to/repo
```

REPL 命令：

```
/help    显示帮助
/memory  显示工作记忆
/session 显示当前 session 路径
/reset   清空当前会话历史与记忆（保留 session id 与工作区）
/exit    退出
```

---

## 命令行参数

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `prompt` | 位置参数；提供时一次性执行，否则进入 REPL | - |
| `--cwd` | 工作区目录 | `.` |
| `--provider` | `deepseek` \| `openai` \| `anthropic` \| `ollama` | `deepseek` |
| `--model` | 模型名覆盖 | provider 默认值 |
| `--base-url` | Provider API base URL | provider 默认值 |
| `--host` | Ollama base URL 便捷参数 | `http://127.0.0.1:11434` |
| `--resume <sessionId\|latest>` | 恢复会话 | - |
| `--approval` | `ask` \| `auto` \| `never` | `ask` |
| `--secret-env-name` | 追加脱敏环境变量名（可重复）；默认自动发现 `*_API_KEY`/`*_TOKEN`/`*_SECRET`/`*_PASSWORD` 环境变量 | - |
| `--max-steps` | 每轮最大工具步数 | `6` |
| `--max-new-tokens` | 模型输出 token 上限 | `512` |
| `--temperature` | 采样温度 | `0.2` |
| `--top-p` | Top-p 采样 | `0.9` |
| `--timeout` | Provider 请求超时（秒） | `300` |

配置优先级：**显式 CLI 参数 > 项目 `.env`（向上搜索）> 系统环境变量 > provider 默认值**。

`.env` 示例：

```bash
export LOOM_CODE_PROVIDER=deepseek
export LOOM_CODE_DEEPSEEK_API_KEY=sk-xxx
export LOOM_CODE_DEEPSEEK_MODEL=deepseek-v4-pro
```

---

## 工具系统

七个工具固定注册顺序：

1. `list_files` — 列出工作区文件（目录优先，忽略大小写排序）
2. `read_file` — 按行范围读取 UTF-8 文件（默认 1–200 行）
3. `search` — `rg` 优先，fallback 大小写不敏感搜索
4. `run_shell` — `/bin/sh -c` 在仓库根执行（过滤环境变量）
5. `write_file` — 创建父目录并覆盖写入
6. `patch_file` — 字面量匹配替换（old_text 必须恰好出现一次）
7. `delegate` — 只读子 Agent 调查（仅 `depth < maxDepth` 时可见）

工具调用格式：

```
<tool>{"name":"read_file","args":{"path":"README.md","start":1,"end":80}}</tool>
<tool name="write_file" path="a.py"><content>...</content></tool>
<final>Done.</final>
```

---

## MCP 外部工具

通过 [Model Context Protocol](https://modelcontextprotocol.io) 接入外部工具服务器（stdio 传输），工具注册进统一的 `ToolRegistry`，审批与脱敏复用内置工具同一链路。默认关闭：

```bash
export MCP_ENABLED=true
```

配置示例（`application.yml` 或环境变量）：

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: ${MCP_ENABLED:false}
        type: sync
        stdio:
          connections:
            github:
              command: npx
              args: ["-y", "@modelcontextprotocol/server-github"]
              env: { GITHUB_PERSONAL_ACCESS_TOKEN: "${GITHUB_TOKEN}" }

loom:
  mcp:
    enabled: ${MCP_ENABLED:false}
    servers:
      github:
        approval-mode: writes          # auto | writes | prompt | approve
        enabled-tools: ["get_issue"]   # 工具白名单（空 = 全部）
        disabled-tools: ["delete_repo"] # 工具黑名单（白名单之后应用）
```

要点：

- **工具命名**：`<server>_<tool>`，如 server `github` 的 `get_issue` → `github_get_issue`；非法字符替换为 `_`。
- **审批映射**：`approval-mode=auto` 的工具不询问；其余（`writes`/`prompt`/`approve`）标记为 risky，走与内置 `run_shell` 相同的审批策略（`--approval ask` 时逐次确认）。`approve` 暂映射为 `writes` 行为。
- **容错**：单个 server 连接或 `tools/list` 失败仅记日志跳过，不阻塞启动；失败工具不注册。
- **工具冲突**：与内置工具重名的 MCP 工具跳过注册。
- **关闭清理**：进程退出时 stdio 子进程随 client 一起终止。
- 当前仅支持 stdio 本地传输；StreamableHTTP/SSE 与 OAuth 为后续版本。

---

## 审批策略

- `auto`：允许所有风险工具。
- `never`：拒绝所有风险工具。
- `ask`：CLI 显示工具名与参数摘要（路径明文、其余参数只显示长度与 hash），输入 `y`/`yes` 允许，其他输入（含 EOF）拒绝；完整 command/write content 与 secret 值不展示。
- `delegate` 子 Agent `readOnly=true`，风险工具直接拒绝。
- 审批动作记录独立 trace 安全事件：`approval_requested`、`approval_granted`、`approval_denied`、`approval_blocked_by_read_only`，事件只携带安全参数摘要。

---

## 会话与存储

```text
.loom-code/
  sessions/
    <sessionId>.json
  runs/
    <runId>/
      task_state.json
      trace.jsonl
      report.json
```

- `--resume latest` 选择当前工作区最近更新的会话；工作区不一致时拒绝恢复。
- REPL 复用同一 session；`/reset` 清空历史/记忆/checkpoint，保留 session id 与工作区。
- 所有持久化 artifact（trace、checkpoint、run、report、session、working/durable memory）在写入前统一脱敏，占位符为 `<redacted>`：自动发现后缀 `API_KEY`/`TOKEN`/`SECRET`/`PASSWORD` 的环境变量值、`--secret-env-name` 指定字段、Provider API key 按长度降序替换；敏感字段名（如 `api_key`）整体替换。
- trace 事件的 `sensitiveRedacted` 按真实处理状态标记（仅当实际替换了秘密才为 `true`），并携带 `redactionVersion`（当前规则版本 `1`）；旧 `[REDACTED]` 标记在重写时统一归一为新占位符，旧 trace JSONL 行（无 `redactionVersion` 字段）仍可被读取。
- 脱敏发生在工具执行边界（工具输出在进入 history/ledger/memory/checkpoint 之前已清洗）；文件 writer 层作为最后防线再次兜底。清洗失败时 fail-closed：原文不会进入模型上下文、记忆、checkpoint 或 trace。
- 工具输出是**不可信数据**：系统指令明确要求把输出中的命令/标签/指令视为数据而非控制协议；疑似 prompt injection（伪造系统指令、忽略规则、泄露 secrets、绕过审批、伪造 `<tool>`/`<final>` 标签）记录 `WARN` 安全事件，不阻断合法内容。

---

## 架构总览

```text
CLI → SessionRuntime → AgentLoop → Prompt/Model → Loom XML Parser → decision → tool_input → tool_execute → tool_output → Tool/FileStore
```

- `decision`：只解析模型协议（final/retry/action），不做工具授权。
- `tool_input`：工具可见性/allowlist、schema 校验、重复调用、read-only 与审批；只把脱敏后的参数写入 event/state。
- `tool_execute`：唯一执行边界，`registry.call` 返回后立即脱敏+截断，步数只在此递增一次。
- `tool_output`：唯一 history/ledger/memory 写入者，消费已清洗的 result。

模块划分：

- `Loom_Agent-app`：非 Web Spring Boot 启动器，CLI/REPL，Spring 仅作 DI 容器。
- `Loom_Agent-domain`：Agent Loop、Loom XML 解析、工具治理、上下文管理。
- `Loom_Agent-infrastructure`：四类 Provider HTTP transport、七个工具实现、`.loom-code` 文件存储。
- `Loom_Agent-types`：公共类型与错误码。

---

## Provider 配置

| Provider | 协议 | 默认 base URL | 默认模型 |
|----------|------|---------------|----------|
| `deepseek` | Anthropic Messages | `https://api.deepseek.com/anthropic` | `deepseek-v4-pro` |
| `openai` | OpenAI Responses | `https://www.right.codes/codex/v1` | `gpt-5.4` |
| `anthropic` | Anthropic Messages | `https://www.right.codes/claude/v1` | `claude-sonnet-4-6` |
| `ollama` | `/api/generate` | `http://127.0.0.1:11434` | `qwen3.5:4b` |

网络错误与 5xx：OpenAI/Anthropic-compatible/DeepSeek 最多重试三次（退避 0.5/1 秒）；Ollama 单次调用。

---

## 常见问题

### CLI 不工作 / 模型调用失败

- 检查 API Key 是否正确配置（`.env` 或环境变量）。
- 检查网络连通性与 base URL。
- Ollama 需先 `ollama serve` 且模型已拉取。

### 会话恢复报 workspace 不匹配

`--resume` 的会话属于另一个工作区。删除或换用正确工作区下的会话。

### 工具被拒绝

- `approval=never` 或 `readOnly` 子 Agent：风险工具（`run_shell`、`write_file`、`patch_file`）会被拒绝。
- 参数校验失败：查看 `error: invalid arguments` 提示并参考给出的调用示例。
- 连续重复调用：第三次相同调用会被拒绝，请换工具或直接给出 final 答案。

---

## 相关文档

- [Agent 循环设计](docs/design/agent-loop.md)

## 许可证

[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
