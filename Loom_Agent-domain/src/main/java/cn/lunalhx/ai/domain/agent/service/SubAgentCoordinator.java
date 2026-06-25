package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentDispatchResult;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentResult;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentTask;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRole;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.SubAgentStatus;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class SubAgentCoordinator {

    private final ModelGateway modelGateway;
    private final RoleToolRegistryFactory toolRegistryFactory;
    private final ApprovalStore approvalStore;
    private final AgentWorkspaceResolver workspaceResolver;
    private final AgentRunRepository runRepository;
    private final AgentCheckpointRepository checkpointRepository;
    private final AgentRuntimeProperties properties;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final TraceRecorder traceRecorder;
    private final BudgetGuard budgetGuard;
    private final AgentMetrics agentMetrics;
    private final ContextWindowManager contextWindowManager;
    // === Phase 2 新字段：供 runChild 创建子 Agent ===
    private final AgentLoopFactory agentLoopFactory;

    // ==================== Phase 2 新生产构造器（5 参数） ====================

    public SubAgentCoordinator(RoleToolRegistryFactory toolRegistryFactory,
                               AgentLoopFactory agentLoopFactory,
                               AgentRuntimeProperties properties,
                               ObjectMapper objectMapper,
                               Executor executor) {
        this.modelGateway = null;
        this.toolRegistryFactory = toolRegistryFactory;
        this.agentLoopFactory = agentLoopFactory;
        this.approvalStore = null;
        this.workspaceResolver = null;
        this.runRepository = null;
        this.checkpointRepository = null;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.traceRecorder = null;
        this.budgetGuard = null;
        this.agentMetrics = null;
        this.contextWindowManager = null;
    }

    // ==================== 旧构造器（标记废弃，最终阶段删除） ====================

    @Deprecated(forRemoval = true)
    public SubAgentCoordinator(ModelGateway modelGateway,
                               RoleToolRegistryFactory toolRegistryFactory,
                               ApprovalStore approvalStore,
                               AgentWorkspaceResolver workspaceResolver,
                               AgentRunRepository runRepository,
                               AgentCheckpointRepository checkpointRepository,
                               AgentRuntimeProperties properties,
                               ObjectMapper objectMapper,
                               Executor executor) {
        this(modelGateway, toolRegistryFactory, approvalStore, workspaceResolver, runRepository, checkpointRepository,
                properties, objectMapper, executor, new InMemoryTraceRecorder(), new DefaultBudgetGuard(properties), new NoopAgentMetrics());
    }

    @Deprecated(forRemoval = true)
    public SubAgentCoordinator(ModelGateway modelGateway,
                               RoleToolRegistryFactory toolRegistryFactory,
                               ApprovalStore approvalStore,
                               AgentWorkspaceResolver workspaceResolver,
                               AgentRunRepository runRepository,
                               AgentCheckpointRepository checkpointRepository,
                               AgentRuntimeProperties properties,
                               ObjectMapper objectMapper,
                               Executor executor,
                               TraceRecorder traceRecorder,
                               BudgetGuard budgetGuard) {
        this(modelGateway, toolRegistryFactory, approvalStore, workspaceResolver, runRepository, checkpointRepository,
                properties, objectMapper, executor, traceRecorder, budgetGuard, new NoopAgentMetrics());
    }

    @Deprecated(forRemoval = true)
    public SubAgentCoordinator(ModelGateway modelGateway,
                               RoleToolRegistryFactory toolRegistryFactory,
                               ApprovalStore approvalStore,
                               AgentWorkspaceResolver workspaceResolver,
                               AgentRunRepository runRepository,
                               AgentCheckpointRepository checkpointRepository,
                               AgentRuntimeProperties properties,
                               ObjectMapper objectMapper,
                               Executor executor,
                               TraceRecorder traceRecorder,
                               BudgetGuard budgetGuard,
                               AgentMetrics agentMetrics) {
        this(modelGateway, toolRegistryFactory, approvalStore, workspaceResolver, runRepository, checkpointRepository,
                properties, objectMapper, executor, traceRecorder, budgetGuard, agentMetrics,
                ContextWindowManager.noop(properties));
    }

    @Deprecated(forRemoval = true)
    public SubAgentCoordinator(ModelGateway modelGateway,
                               RoleToolRegistryFactory toolRegistryFactory,
                               ApprovalStore approvalStore,
                               AgentWorkspaceResolver workspaceResolver,
                               AgentRunRepository runRepository,
                               AgentCheckpointRepository checkpointRepository,
                               AgentRuntimeProperties properties,
                               ObjectMapper objectMapper,
                               Executor executor,
                               TraceRecorder traceRecorder,
                               BudgetGuard budgetGuard,
                               AgentMetrics agentMetrics,
                               ContextWindowManager contextWindowManager) {
        this.modelGateway = modelGateway;
        this.toolRegistryFactory = toolRegistryFactory;
        this.approvalStore = approvalStore;
        this.workspaceResolver = workspaceResolver;
        this.runRepository = runRepository;
        this.checkpointRepository = checkpointRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.traceRecorder = traceRecorder == null ? new InMemoryTraceRecorder() : traceRecorder;
        this.budgetGuard = budgetGuard == null ? new DefaultBudgetGuard(properties) : budgetGuard;
        this.agentMetrics = agentMetrics == null ? new NoopAgentMetrics() : agentMetrics;
        this.contextWindowManager = contextWindowManager == null ? ContextWindowManager.noop(properties) : contextWindowManager;
        this.agentLoopFactory = null;
    }

    public SubAgentDispatchResult dispatch(AgentContext parent) {
        long startedAt = System.currentTimeMillis();
        if (!Boolean.TRUE.equals(properties.getSubAgentEnabled())) {
            return SubAgentDispatchResult.failure("sub_agent_disabled", "子 Agent 功能未启用", elapsed(startedAt));
        }
        if (parent.getAgentDepth() >= positive(properties.getSubAgentMaxDepth(), 1)) {
            return SubAgentDispatchResult.failure("sub_agent_depth_exceeded", "已达到子 Agent 最大派生深度", elapsed(startedAt));
        }

        DispatchRequest request = parse(parent.getDecision());
        if (request.errorCode != null) {
            return SubAgentDispatchResult.failure(request.errorCode, request.errorMessage, elapsed(startedAt));
        }
        if (request.tasks.size() > positive(properties.getSubAgentMaxChildren(), 6)) {
            return SubAgentDispatchResult.failure("sub_agent_too_many_tasks", "子任务数量超过上限", elapsed(startedAt));
        }
        if (containsEditor(request.tasks) && (request.tasks.size() > 1 || request.maxConcurrency > 1)) {
            return SubAgentDispatchResult.failure("sub_agent_editor_parallel_denied",
                    "编辑型子 Agent 只能单个串行执行", elapsed(startedAt));
        }

        int concurrency = Math.max(1, Math.min(request.maxConcurrency, positive(properties.getSubAgentMaxConcurrency(), 4)));
        Semaphore semaphore = new Semaphore(concurrency);
        List<CompletableFuture<SubAgentResult>> futures = new ArrayList<>();
        for (int i = 0; i < request.tasks.size(); i++) {
            SubAgentTask task = request.tasks.get(i);
            int ordinal = i + 1;
            futures.add(CompletableFuture.supplyAsync(() -> runWithPermit(parent, task, ordinal, semaphore), executor));
        }

        long timeoutMs = positive(properties.getSubAgentTimeoutMs(), 60000L);
        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        try {
            all.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            futures.forEach(future -> future.cancel(true));
        }

        List<SubAgentResult> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<SubAgentResult> future = futures.get(i);
            if (future.isDone() && !future.isCancelled()) {
                try {
                    results.add(future.getNow(timeoutResult(request.tasks.get(i), null, 0L)));
                } catch (Exception e) {
                    results.add(failedResult(request.tasks.get(i), null, "sub_agent_failed", e.getMessage(), 0L));
                }
            } else {
                future.cancel(true);
                results.add(timeoutResult(request.tasks.get(i), null, timeoutMs));
            }
        }

        String observation = toObservation(parent, request.reason, results);
        boolean truncated = false;
        int maxChars = positive(properties.getSubAgentSummaryMaxChars(), 12000);
        if (StringUtils.length(observation) > maxChars) {
            observation = StringUtils.abbreviate(observation, maxChars);
            truncated = true;
        }
        boolean anySucceeded = results.stream().anyMatch(result -> result.getStatus() == SubAgentStatus.SUCCEEDED);
        return SubAgentDispatchResult.builder()
                .success(anySucceeded)
                .errorCode(anySucceeded ? null : "sub_agent_all_failed")
                .message(anySucceeded ? null : "所有子 Agent 均未成功完成")
                .observation(observation)
                .truncated(truncated)
                .elapsedMs(elapsed(startedAt))
                .results(results)
                .build();
    }

    private SubAgentResult runWithPermit(AgentContext parent,
                                         SubAgentTask task,
                                         int ordinal,
                                         Semaphore semaphore) {
        long startedAt = System.currentTimeMillis();
        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;
            return runChild(parent, task, ordinal, startedAt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failedResult(task, null, "sub_agent_interrupted", "子 Agent 被中断", elapsed(startedAt));
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    private SubAgentResult runChild(AgentContext parent, SubAgentTask task, int ordinal, long startedAt) {
        String childRunId = UUID.randomUUID().toString();
        try {
            AgentRole role = task.getRole() == null ? AgentRole.EXPLORER : task.getRole();
            ToolRegistry childRegistry = toolRegistryFactory.create(role);
            DefaultAgentLoopService childService;
            if (agentLoopFactory != null) {
                childService = agentLoopFactory.createChild(childRegistry);
            } else {
                // 旧构造器兼容路径：直接 new（最终阶段删除）
                childService = new DefaultAgentLoopService(
                        modelGateway, childRegistry, approvalStore, workspaceResolver,
                        runRepository, checkpointRepository, properties, objectMapper,
                        Runnable::run, null, traceRecorder, budgetGuard, agentMetrics,
                        contextWindowManager);
            }
            List<AgentEvent> events = childService.ask(childQuestion(parent, task, ordinal, childRunId, role))
                    .onErrorResume(error -> Flux.just(AgentEvent.builder()
                            .type(AgentEventType.ERROR)
                            .runId(childRunId)
                            .code("sub_agent_error")
                            .message(error.getMessage())
                            .build()))
                    .collectList()
                    .block(Duration.ofMillis(positive(properties.getSubAgentTimeoutMs(), 60000L) + 1000L));
            if (events == null) {
                return timeoutResult(task, childRunId, elapsed(startedAt));
            }
            AgentEvent answer = events.stream()
                    .filter(event -> event.getType() == AgentEventType.ANSWER)
                    .findFirst()
                    .orElse(null);
            if (answer != null && StringUtils.isNotBlank(answer.getAnswer())) {
                return SubAgentResult.builder()
                        .taskId(task.getTaskId())
                        .runId(childRunId)
                        .role(role)
                        .status(SubAgentStatus.SUCCEEDED)
                        .summary(clamp(answer.getAnswer()))
                        .truncated(StringUtils.length(answer.getAnswer()) > positive(properties.getSubAgentSummaryMaxChars(), 12000))
                        .stepCount(stepCount(events))
                        .elapsedMs(elapsed(startedAt))
                        .build();
            }
            AgentEvent error = events.stream()
                    .filter(event -> event.getType() == AgentEventType.ERROR)
                    .findFirst()
                    .orElse(null);
            return failedResult(task, childRunId,
                    error == null ? "sub_agent_no_answer" : error.getCode(),
                    error == null ? "子 Agent 未生成 final answer" : error.getMessage(),
                    elapsed(startedAt));
        } catch (Exception e) {
            return failedResult(task, childRunId, "sub_agent_failed", e.getMessage(), elapsed(startedAt));
        }
    }

    private AgentQuestion childQuestion(AgentContext parent,
                                        SubAgentTask task,
                                        int ordinal,
                                        String childRunId,
                                        AgentRole role) {
        String rootRunId = StringUtils.defaultIfBlank(parent.getRootRunId(), parent.getRunId());
        return AgentQuestion.builder()
                .runId(childRunId)
                .parentRunId(parent.getRunId())
                .rootRunId(rootRunId)
                .requestId(UUID.randomUUID().toString())
                .conversationId(parent.getConversationId())
                .traceId(parent.getTraceId())
                .agentRole(role)
                .agentDepth(parent.getAgentDepth() + 1)
                .childOrdinal(ordinal)
                .question(renderChildTask(parent, task, role))
                .pathScope(task.getPathScope())
                .workspace(parent.getResolvedWorkspace() == null ? null : parent.getResolvedWorkspace().toString())
                .maxSteps(task.getMaxSteps() == null ? parent.getMaxSteps() : task.getMaxSteps())
                .includeTrace(false)
                .subAgentSpawnAllowed(false)
                .build();
    }

    private String renderChildTask(AgentContext parent, SubAgentTask task, AgentRole role) {
        StringBuilder text = new StringBuilder();
        text.append("父 Agent 任务：").append(parent.getQuestion()).append('\n');
        text.append("你的角色：").append(role.name()).append('\n');
        text.append("你的子任务：").append(task.getQuestion()).append('\n');
        if (StringUtils.isNotBlank(task.getPathScope())) {
            text.append("路径范围：只在 ").append(task.getPathScope()).append(" 下探索；搜索工具请显式传入该 pathScope。\n");
        }
        if (StringUtils.isNotBlank(task.getExpectedOutput())) {
            text.append("期望输出：").append(task.getExpectedOutput()).append('\n');
        }
        text.append("最终只能输出 final JSON，answer 字段里放一个 JSON 字符串，包含 summary、findings、confidence、truncated、followUp。");
        return text.toString();
    }

    private DispatchRequest parse(AgentDecision decision) {
        DispatchRequest request = new DispatchRequest();
        JsonNode input = decision == null ? null : decision.getInput();
        if (input == null || input.isMissingNode() || !input.path("tasks").isArray()) {
            request.errorCode = "sub_agent_tasks_required";
            request.errorMessage = "spawn_agents.input.tasks 必须是数组";
            return request;
        }
        request.reason = text(input, "reason", "未说明");
        request.maxConcurrency = Math.max(1, input.path("maxConcurrency").asInt(2));
        JsonNode tasks = input.path("tasks");
        for (int i = 0; i < tasks.size(); i++) {
            JsonNode taskNode = tasks.get(i);
            String question = text(taskNode, "question", "");
            if (StringUtils.isBlank(question)) {
                request.errorCode = "sub_agent_task_question_required";
                request.errorMessage = "子任务 question 不能为空";
                return request;
            }
            request.tasks.add(SubAgentTask.builder()
                    .taskId(StringUtils.defaultIfBlank(text(taskNode, "taskId", null), "task-" + (i + 1)))
                    .role(parseRole(text(taskNode, "role", "explorer")))
                    .question(question)
                    .pathScope(text(taskNode, "pathScope", null))
                    .expectedOutput(text(taskNode, "expectedOutput", null))
                    .maxSteps(taskNode.path("maxSteps").isNumber() ? Math.max(1, taskNode.path("maxSteps").asInt()) : null)
                    .build());
        }
        if (request.tasks.isEmpty()) {
            request.errorCode = "sub_agent_tasks_required";
            request.errorMessage = "至少需要一个子任务";
        }
        return request;
    }

    private AgentRole parseRole(String value) {
        try {
            return AgentRole.valueOf(StringUtils.upperCase(StringUtils.defaultIfBlank(value, "explorer")));
        } catch (Exception e) {
            return AgentRole.EXPLORER;
        }
    }

    private boolean containsEditor(List<SubAgentTask> tasks) {
        return tasks.stream().anyMatch(task -> task.getRole() == AgentRole.EDITOR);
    }

    private String toObservation(AgentContext parent, String reason, List<SubAgentResult> results) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "sub_agent_summary");
        root.put("parentRunId", parent.getRunId());
        root.put("reason", reason);
        root.put("total", results.size());
        root.put("succeeded", results.stream().filter(result -> result.getStatus() == SubAgentStatus.SUCCEEDED).count());
        root.put("failed", results.stream().filter(result -> result.getStatus() != SubAgentStatus.SUCCEEDED).count());
        List<Map<String, Object>> resultViews = new ArrayList<>();
        for (SubAgentResult result : results) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("taskId", result.getTaskId());
            view.put("runId", result.getRunId());
            view.put("role", result.getRole() == null ? null : result.getRole().name());
            view.put("status", result.getStatus() == null ? null : result.getStatus().name());
            view.put("summary", result.getSummary());
            view.put("errorCode", result.getErrorCode());
            view.put("message", result.getMessage());
            view.put("truncated", result.isTruncated());
            view.put("stepCount", result.getStepCount());
            view.put("elapsedMs", result.getElapsedMs());
            resultViews.add(view);
        }
        root.put("results", resultViews);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return String.valueOf(root);
        }
    }

    private SubAgentResult failedResult(SubAgentTask task, String runId, String code, String message, long elapsedMs) {
        return SubAgentResult.builder()
                .taskId(task.getTaskId())
                .runId(runId)
                .role(task.getRole())
                .status(SubAgentStatus.FAILED)
                .errorCode(StringUtils.defaultIfBlank(code, "sub_agent_failed"))
                .message(StringUtils.defaultIfBlank(message, "子 Agent 执行失败"))
                .elapsedMs(elapsedMs)
                .build();
    }

    private SubAgentResult timeoutResult(SubAgentTask task, String runId, long elapsedMs) {
        return SubAgentResult.builder()
                .taskId(task.getTaskId())
                .runId(runId)
                .role(task.getRole())
                .status(SubAgentStatus.TIMEOUT)
                .errorCode("sub_agent_timeout")
                .message("子 Agent 执行超时")
                .elapsedMs(elapsedMs)
                .build();
    }

    private String text(JsonNode node, String field, String defaultValue) {
        if (node == null || node.path(field).isMissingNode() || node.path(field).isNull()) {
            return defaultValue;
        }
        return node.path(field).asText(defaultValue);
    }

    private int stepCount(List<AgentEvent> events) {
        return events.stream()
                .filter(event -> event.getType() == AgentEventType.DONE)
                .findFirst()
                .map(AgentEvent::getStepCount)
                .orElse(0);
    }

    private String clamp(String text) {
        int maxChars = positive(properties.getSubAgentSummaryMaxChars(), 12000);
        return StringUtils.length(text) > maxChars ? StringUtils.abbreviate(text, maxChars) : text;
    }

    private int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private long positive(Long value, long fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }

    private static class DispatchRequest {
        private String reason;
        private int maxConcurrency;
        private String errorCode;
        private String errorMessage;
        private final List<SubAgentTask> tasks = new ArrayList<>();
    }

}
