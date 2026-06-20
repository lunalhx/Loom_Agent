# Loom Agent

Loom Agent 是一个基于 Java 17、Spring Boot 3 和 DeepSeek 的后端 Agent 骨架。当前版本先把单 Agent 的最薄链路做稳：通过一个 SSE 接口把用户消息发给 DeepSeek，并逐字返回模型输出。

开发原则：每一步都保持项目可运行、可演示。先做单 Agent，再逐步接入代码检索、工具调用和多 Agent 编排。当前已提供一个节点化 ReAct Agent Loop，可用只读工具分析项目代码。

## 模块结构

- `Loom_Agent-api`：对外 DTO、统一响应对象。
- `Loom_Agent-trigger`：HTTP Controller、SSE 协议适配、请求参数兜底。
- `Loom_Agent-domain`：会话流式服务、模型调用端口、重试/超时/输出格式校验。
- `Loom_Agent-infrastructure`：DeepSeek 网关实现、只读文件工具、MyBatis DAO、Redis 缓存占位。
- `Loom_Agent-app`：Spring Boot 启动、配置绑定、线程池、MyBatis 扫描。
- `Loom_Agent-types`：通用异常、响应码、常量。

## 本地运行

1. 准备环境变量：

```bash
cp docs/env/.env.example docs/env/.env
```

然后把 `docs/env/.env` 中的 `DEEPSEEK_API_KEY` 改成自己的 DeepSeek API Key。真实 `.env` 已加入 `.gitignore`，提交时只提交 `.env.example`。

Docker 资源默认使用 `COMPOSE_PROJECT_NAME=loom-agent` 隔离，容器名会是 `loom-agent-mysql`、`loom-agent-redis`、`loom-agent-app`。如果本机已有其他项目占用端口，只改 `.env` 里的宿主机端口即可：

```bash
APP_HOST_PORT=8091
MYSQL_HOST_PORT=13306
REDIS_HOST_PORT=16379
PHPMYADMIN_HOST_PORT=8899
REDIS_ADMIN_HOST_PORT=8081
```

2. 启动 MySQL 和 Redis：

```bash
cd docs/dev-ops
docker-compose --env-file ../env/.env -f docker-compose-environment.yml up -d
```

3. 启动应用：

```bash
mvn -pl Loom_Agent-app -am spring-boot:run
```

4. 调用流式接口：

```bash
curl -N \
  -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8091/api/v1/chat/stream \
  -d '{"message":"用一句话介绍你自己"}'
```

5. 调用代码分析 Agent：

```bash
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

节点化 ReAct 代码分析 Agent。请求字段：

- `question`：必填，代码分析问题。
- `workspace`：预留字段，当前默认使用 `AGENT_WORKSPACE_ROOT`。
- `maxSteps`：可选，最大工具调用步数。
- `includeTrace`：可选，是否返回 `thought/tool_call/observation` 等中间事件。

Agent SSE 事件：

- `meta`：返回请求元信息。
- `node_start`：节点开始执行，包含 `node` 和 `nodeInputs`，`includeTrace=true` 时返回。
- `thought`：下一步行动意图摘要。
- `tool_call`：工具名和参数。
- `observation`：工具观察结果。
- `answer`：最终回答。
- `done`：结束原因和步数。
- `error`：兜底错误。

## 健壮性

- 超时：`AI_CONNECT_TIMEOUT_MS`、`AI_FIRST_TOKEN_TIMEOUT_MS`、`AI_STREAM_TIMEOUT_MS`。
- 重试：默认最多 3 次，只在首 token 输出前重试；429、500、503 会重试，400、401、402、422 不重试。
- 输出校验：空输出会返回 `output_empty`；`responseFormat=JSON_OBJECT` 时会校验完整输出是否为合法 JSON。
- 异常兜底：鉴权失败、余额不足、限流、服务过载、内容过滤、输出截断和格式错误都会映射为 SSE `error` 事件。

更多设计细节见 [backend-architecture.md](docs/design/backend-architecture.md)。
Agent Loop 设计见 [agent-loop.md](docs/design/agent-loop.md)。
