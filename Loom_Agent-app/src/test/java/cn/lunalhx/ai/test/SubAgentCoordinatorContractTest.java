package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentDispatchResult;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRole;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.SubAgentStatus;
import cn.lunalhx.ai.domain.agent.service.AgentWorkspaceResolver;
import cn.lunalhx.ai.domain.agent.service.InMemoryAgentRunRepository;
import cn.lunalhx.ai.domain.agent.service.InMemoryApprovalStore;
import cn.lunalhx.ai.domain.agent.service.InMemoryAgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.service.RoleToolRegistryFactory;
import cn.lunalhx.ai.domain.agent.service.SubAgentCoordinator;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * SubAgentCoordinator 独立契约（Phase 1 §4）。
 *
 * <p>直接测试 {@link SubAgentCoordinator#dispatch(AgentContext)}，不经过 Agent Loop。
 * 覆盖：非法 JSON / 缺 tasks / 未知角色 / 超过最大子任务数 / Editor 禁并行 /
 * 并发上限 / 总超时 / 部分成功 / 全部失败 / 结果顺序 / observation 截断 /
 * 子 Agent 不继承父动态文本 / 只读角色不获得写工具。
 *
 * <p>并发测试使用 {@link CountDownLatch} 与计数器，不使用固定 sleep 判断并发。
 */
public class SubAgentCoordinatorContractTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    // ===== 解析与前置拦截 =====

    @Test
    public void invalidJsonOrMissingTasksShouldReturnStableError() {
        SubAgentCoordinator coordinator = coordinator(true);
        // decision.input 不是数组 -> sub_agent_tasks_required
        AgentContext parent = parentWithDecision(objectNode().put("reason", "x"));
        SubAgentDispatchResult result = coordinator.dispatch(parent);
        assertFalse(result.isSuccess());
        assertEquals("sub_agent_tasks_required", result.getErrorCode());

        // tasks 为空数组 -> sub_agent_tasks_required（至少需要一个子任务）
        AgentContext parentEmpty = parentWithDecision(objectNode().put("reason", "x").set("tasks", arrayNode()));
        SubAgentDispatchResult empty = coordinator.dispatch(parentEmpty);
        assertFalse(empty.isSuccess());
        assertEquals("sub_agent_tasks_required", empty.getErrorCode());
    }

    @Test
    public void taskMissingQuestionShouldReturnStableError() {
        SubAgentCoordinator coordinator = coordinator(true);
        ObjectNode input = spawnInput(1);
        ((ObjectNode) input.get("tasks").get(0)).remove("question");
        AgentContext parent = parentWithDecision(input);
        SubAgentDispatchResult result = coordinator.dispatch(parent);
        assertFalse(result.isSuccess());
        assertEquals("sub_agent_task_question_required", result.getErrorCode());
    }

    @Test
    public void unknownRoleShouldFallBackToExplorer() {
        // parseRole 对未知值兜底为 EXPLORER（不报错），结果按 explorer 处理
        SubAgentCoordinator coordinator = coordinatorWithGateway(finalAnswerGateway("summary", "ok"));
        ObjectNode input = spawnInput(1);
        ((ObjectNode) input.get("tasks").get(0)).put("role", "totally-unknown-role");
        AgentContext parent = parentWithDecision(input);
        SubAgentDispatchResult result = coordinator.dispatch(parent);
        assertTrue(result.isSuccess());
        assertEquals(AgentRole.EXPLORER, result.getResults().get(0).getRole());
    }

    @Test
    public void exceedingMaxChildrenShouldBeRejected() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.setSubAgentMaxChildren(2);
        SubAgentCoordinator coordinator = coordinatorWithGateway(properties, finalAnswerGateway("summary", "ok"));
        ObjectNode input = spawnInput(3);
        AgentContext parent = parentWithDecision(input);
        SubAgentDispatchResult result = coordinator.dispatch(parent);
        assertFalse(result.isSuccess());
        assertEquals("sub_agent_too_many_tasks", result.getErrorCode());
    }

    @Test
    public void editorSubAgentMustNotRunInParallel() {
        SubAgentCoordinator coordinator = coordinatorWithGateway(finalAnswerGateway("summary", "ok"));
        // 2 个 editor 任务 -> editor_parallel_denied
        ObjectNode input = spawnInput(2);
        for (int i = 0; i < 2; i++) {
            ((ObjectNode) input.get("tasks").get(i)).put("role", "editor");
        }
        AgentContext parent = parentWithDecision(input);
        SubAgentDispatchResult result = coordinator.dispatch(parent);
        assertFalse(result.isSuccess());
        assertEquals("sub_agent_editor_parallel_denied", result.getErrorCode());

        // 单个 editor + maxConcurrency=2 -> editor_parallel_denied
        ObjectNode single = spawnInput(1);
        ((ObjectNode) single.get("tasks").get(0)).put("role", "editor");
        single.put("maxConcurrency", 2);
        SubAgentDispatchResult singleResult = coordinator.dispatch(parentWithDecision(single));
        assertFalse(singleResult.isSuccess());
        assertEquals("sub_agent_editor_parallel_denied", singleResult.getErrorCode());
    }

    // ===== 并发上限 =====

    @Test
    public void actualConcurrencyMustNotExceedConfigured() throws InterruptedException {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.setSubAgentMaxConcurrency(2);
        properties.setSubAgentTimeoutMs(5000L);
        int taskCount = 4;
        CountDownLatch enterLatch = new CountDownLatch(2);
        CountDownLatch proceedLatch = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                int now = concurrent.incrementAndGet();
                maxConcurrent.accumulateAndGet(now, Math::max);
                enterLatch.countDown();
                try {
                    proceedLatch.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                concurrent.decrementAndGet();
                return Mono.just(ModelChatResult.builder()
                        .content(finalAnswerJson("ok"))
                        .finishReason("stop")
                        .build());
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        try {
            SubAgentCoordinator coordinator = new SubAgentCoordinator(
                    gateway,
                    new RoleToolRegistryFactory(List.of(fakeTool("code_search", "ok"))),
                    new InMemoryApprovalStore(),
                    new AgentWorkspaceResolver(properties),
                    new InMemoryAgentRunRepository(),
                    new InMemoryAgentCheckpointRepository(),
                    properties,
                    new ObjectMapper(),
                    executor);
            AtomicReference<SubAgentDispatchResult> ref = new AtomicReference<>();
            Thread runner = new Thread(() -> ref.set(coordinator.dispatch(parentWithDecision(spawnInput(taskCount)))));
            runner.start();
            // 等到恰好 2 个子任务进入（并发上限），确认第 3、4 个还未进入
            assertTrue("2 个子任务应先进入并发执行", enterLatch.await(3, TimeUnit.SECONDS));
            Thread.sleep(80); // 让可能越界的第 3 个有机会进入（若有 bug）
            assertEquals("并发数不得超过 subAgentMaxConcurrency=2", 2, maxConcurrent.get());
            proceedLatch.countDown(); // 放行
            runner.join(5000);
            SubAgentDispatchResult result = ref.get();
            assertNotNull(result);
            assertTrue(result.isSuccess());
            assertEquals(taskCount, result.getResults().size());
            assertTrue("实际最大并发=" + maxConcurrent.get() + " 应 <= 2", maxConcurrent.get() <= 2);
        } finally {
            executor.shutdownNow();
        }
    }

    // ===== 超时 =====

    @Test
    public void totalTimeoutShouldCancelUnfinishedAndReturnTimeoutResult() {
        // 必须用真实异步 Executor：Runnable::run 会同步阻塞调度线程，使 allOf.get 超时失效。
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.setSubAgentTimeoutMs(100L);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            SubAgentCoordinator coordinator = new SubAgentCoordinator(
                    new ModelGateway() {
                        @Override
                        public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                            return Flux.empty();
                        }

                        @Override
                        public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                            return Mono.fromCallable(() -> {
                                Thread.sleep(2000); // 远超 100ms 超时
                                return ModelChatResult.builder().content(finalAnswerJson("ok")).finishReason("stop").build();
                            });
                        }
                    },
                    new RoleToolRegistryFactory(List.of(fakeTool("code_search", "ok"))),
                    new InMemoryApprovalStore(),
                    new AgentWorkspaceResolver(properties),
                    new InMemoryAgentRunRepository(),
                    new InMemoryAgentCheckpointRepository(),
                    properties,
                    new ObjectMapper(),
                    executor);
            AgentContext parent = parentWithDecision(spawnInput(1));
            SubAgentDispatchResult result = coordinator.dispatch(parent);
            // 子任务被超时取消，全部失败
            assertFalse(result.isSuccess());
            assertEquals("sub_agent_all_failed", result.getErrorCode());
            assertEquals(SubAgentStatus.TIMEOUT, result.getResults().get(0).getStatus());
        } finally {
            executor.shutdownNow();
        }
    }

    // ===== 部分成功 / 全部失败 =====

    @Test
    public void partialSuccessShouldReportOverallSuccess() {
        // 2 个任务：第 1 个成功，第 2 个 gateway 抛错 -> FAILED
        ModelGateway gateway = new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                if (prompt.getMessage().contains("fail-me")) {
                    return Mono.error(new RuntimeException("boom"));
                }
                return Mono.just(ModelChatResult.builder().content(finalAnswerJson("ok")).finishReason("stop").build());
            }
        };
        SubAgentCoordinator coordinator = coordinatorWithGateway(gateway);
        ObjectNode input = spawnInput(2);
        ((ObjectNode) input.get("tasks").get(1)).put("question", "fail-me");
        AgentContext parent = parentWithDecision(input);
        SubAgentDispatchResult result = coordinator.dispatch(parent);
        // 部分成功 -> 整体成功
        assertTrue(result.isSuccess());
        assertEquals(2, result.getResults().size());
        // 结果保持原始顺序：task-1 成功，task-2 失败
        assertEquals("task-1", result.getResults().get(0).getTaskId());
        assertEquals(SubAgentStatus.SUCCEEDED, result.getResults().get(0).getStatus());
        assertEquals("task-2", result.getResults().get(1).getTaskId());
        assertEquals(SubAgentStatus.FAILED, result.getResults().get(1).getStatus());
    }

    @Test
    public void allFailedShouldReturnSubAgentAllFailed() {
        // 所有任务 gateway 抛错 -> 全部 FAILED
        ModelGateway gateway = new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.error(new RuntimeException("boom"));
            }
        };
        SubAgentCoordinator coordinator = coordinatorWithGateway(gateway);
        AgentContext parent = parentWithDecision(spawnInput(2));
        SubAgentDispatchResult result = coordinator.dispatch(parent);
        assertFalse(result.isSuccess());
        assertEquals("sub_agent_all_failed", result.getErrorCode());
        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().stream().allMatch(r -> r.getStatus() == SubAgentStatus.FAILED));
    }

    // ===== 结果顺序 =====

    @Test
    public void aggregatedResultsShouldPreserveOriginalTaskOrder() {
        ModelGateway gateway = new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                String message = prompt.getMessage();
                // renderChildTask 会写入 "你的子任务：<question>"，question 为 "搜索使用点 N"
                String tag = message.contains("搜索使用点 1") ? "s1"
                        : message.contains("搜索使用点 2") ? "s2" : "s3";
                return Mono.just(ModelChatResult.builder()
                        .content(finalAnswerJson(tag))
                        .finishReason("stop")
                        .build());
            }
        };
        SubAgentCoordinator coordinator = coordinatorWithGateway(gateway);
        AgentContext parent = parentWithDecision(spawnInput(3));
        SubAgentDispatchResult result = coordinator.dispatch(parent);
        assertTrue(result.isSuccess());
        // 结果保持任务原始顺序 task-1/2/3
        assertEquals(List.of("task-1", "task-2", "task-3"),
                result.getResults().stream().map(SubAgentResult::getTaskId).toList());
        // 每个子结果的 summary 是子 Agent 的 answer JSON 字符串，含对应 tag
        assertTrue(result.getResults().get(0).getSummary().contains("\"summary\":\"s1\""));
        assertTrue(result.getResults().get(2).getSummary().contains("\"summary\":\"s3\""));
    }

    // ===== observation 截断 =====

    @Test
    public void observationOverLimitShouldBeTruncatedWithFlag() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.setSubAgentSummaryMaxChars(200);
        // 单个任务，answer 很长，触发 observation 截断（toObservation 按 maxChars abbreviate）
        SubAgentCoordinator coordinator = coordinatorWithGateway(properties, new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                String big = "x".repeat(50000);
                return Mono.just(ModelChatResult.builder()
                        .content(finalAnswerJson(big))
                        .finishReason("stop")
                        .build());
            }
        });
        AgentContext parent = parentWithDecision(spawnInput(1));
        SubAgentDispatchResult result = coordinator.dispatch(parent);
        assertTrue(result.isSuccess());
        // observation 被截断并设置 truncated 标志
        assertTrue("observation 长度应在 maxChars 附近，实际=" + result.getObservation().length(),
                result.getObservation().length() <= properties.getSubAgentSummaryMaxChars() + 100);
        assertTrue("整体 observation truncated 标志应置位", result.isTruncated());
        // 子结果 summary 被截断（truncated=true）
        assertTrue(result.getResults().get(0).isTruncated());
    }

    // ===== 子 Agent 不继承父动态文本 =====

    @Test
    public void childShouldNotInheritParentDynamicText() {
        List<String> prompts = new java.util.concurrent.CopyOnWriteArrayList<>();
        SubAgentCoordinator coordinator = coordinatorWithGateway(promptCapturingGateway(prompts, "ok"));
        AgentContext parent = parentWithDecision(spawnInput(1));
        parent.getDynamicText().appendSystemNote(1, "test", "Parent Secret", "SHOULD_NOT_LEAK_TO_CHILD");
        coordinator.dispatch(parent);
        // 子 Agent 提示词中不得包含父动态文本中的机密内容
        assertTrue(prompts.stream()
                .filter(prompt -> prompt.contains("隔离子 Agent"))
                .noneMatch(prompt -> prompt.contains("SHOULD_NOT_LEAK_TO_CHILD")));
    }

    // ===== 只读角色不获得写工具 =====

    @Test
    public void readOnlyRoleMustNotObtainWriteTools() {
        AtomicInteger calls = new AtomicInteger();
        RoleToolRegistryFactory factory = new RoleToolRegistryFactory(List.of(
                fakeWriteTool("run_shell", "should not write", calls),
                fakeTool("code_search", "ok")));
        // explorer 为只读角色：run_shell 被包装为只读，调用被拒
        ToolCall call = ToolCall.builder()
                .name("run_shell")
                .input(new ObjectMapper().createObjectNode().put("path", "Demo.java"))
                .workspaceRoot(Path.of(".").toAbsolutePath().normalize())
                .build();
        ToolPolicyDecision policy = factory.create(AgentRole.EXPLORER).policy(call);
        ToolResult toolResult = factory.create(AgentRole.EXPLORER).call(call);
        assertEquals(ToolPermissionLevel.HIGH_RISK_DENY, policy.getPermissionLevel());
        assertEquals(0, calls.get());
        assertTrue(toolResult.getObservation().contains("sub_agent_read_only_violation"));

        // editor 非只读角色：写工具保留
        ToolPolicyDecision editorPolicy = factory.create(AgentRole.EDITOR).policy(call);
        assertFalse(editorPolicy.getPermissionLevel() == ToolPermissionLevel.HIGH_RISK_DENY);
    }

    // ===== 辅助 =====

    private SubAgentCoordinator coordinator(boolean subAgentEnabled) {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.setSubAgentEnabled(subAgentEnabled);
        return coordinatorWithGateway(properties, finalAnswerGateway("summary", "ok"));
    }

    private SubAgentCoordinator coordinatorWithGateway(ModelGateway gateway) {
        return coordinatorWithGateway(AgentRuntimeTestFixture.standardProperties(), gateway);
    }

    private SubAgentCoordinator coordinatorWithGateway(AgentRuntimeProperties properties, ModelGateway gateway) {
        return new SubAgentCoordinator(
                gateway,
                new RoleToolRegistryFactory(List.of(fakeTool("code_search", "ok"))),
                new InMemoryApprovalStore(),
                new AgentWorkspaceResolver(properties),
                new InMemoryAgentRunRepository(),
                new InMemoryAgentCheckpointRepository(),
                properties,
                new ObjectMapper(),
                Runnable::run);
    }

    private ModelGateway finalAnswerGateway(String summary, String findings) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.just(ModelChatResult.builder()
                        .content(finalAnswerJson(summary))
                        .finishReason("stop")
                        .build());
            }
        };
    }

    private ModelGateway promptCapturingGateway(List<String> prompts, String summary) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                prompts.add(prompt.getMessage());
                return Mono.just(ModelChatResult.builder()
                        .content(finalAnswerJson(summary))
                        .finishReason("stop")
                        .build());
            }
        };
    }

    private String finalAnswerJson(String summary) {
        return "{\"type\":\"final\",\"answer\":\"{\\\"summary\\\":\\\"" + summary
                + "\\\",\\\"findings\\\":[],\\\"confidence\\\":\\\"high\\\",\\\"truncated\\\":false,\\\"followUp\\\":\\\"\\\"}\",\"evidence\":[]}";
    }

    private ObjectNode objectNode() {
        return new ObjectMapper().createObjectNode();
    }

    private ArrayNode arrayNode() {
        return new ObjectMapper().createArrayNode();
    }

    /** 构造一个 spawn_agents 决策 input，含 N 个 explorer 子任务。 */
    private ObjectNode spawnInput(int taskCount) {
        ObjectNode input = objectNode();
        input.put("reason", "搜索 DeprecatedApi");
        input.put("maxConcurrency", Math.min(taskCount, 4));
        ArrayNode tasks = input.putArray("tasks");
        for (int i = 1; i <= taskCount; i++) {
            tasks.addObject()
                    .put("taskId", "task-" + i)
                    .put("role", "explorer")
                    .put("question", "搜索使用点 " + i)
                    .put("pathScope", ".");
        }
        return input;
    }

    private AgentContext parentWithDecision(ObjectNode input) {
        AgentContext parent = new AgentContext();
        parent.setRunId("parent-" + UUID.randomUUID());
        parent.setRootRunId(parent.getRunId());
        parent.setRequestId("request-" + parent.getRunId());
        parent.setConversationId("conversation-" + parent.getRunId());
        parent.setQuestion("搜集 DeprecatedApi 的使用点");
        parent.setResolvedWorkspace(Path.of(".").toAbsolutePath().normalize());
        parent.setWorkspaceDisplayName(".");
        parent.setMaxSteps(3);
        parent.setStartedAt(java.time.Instant.now());
        parent.setAgentDepth(0);
        parent.setDecision(AgentDecision.builder()
                .type("action")
                .tool("spawn_agents")
                .input(input)
                .inputView(Map.of())
                .build());
        return parent;
    }

    private AgentTool fakeTool(String name, String observation) {
        return new AgentTool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.builder().name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public ToolResult call(ToolCall call) {
                return ToolResult.success(observation, false, 1L);
            }
        };
    }

    private AgentTool fakeWriteTool(String name, String observation, AtomicInteger calls) {
        return new AgentTool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.builder().name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public ToolPolicyDecision policy(ToolCall call) {
                return ToolPolicyDecision.writeConfirm("write", name);
            }

            @Override
            public ToolResult call(ToolCall call) {
                calls.incrementAndGet();
                return ToolResult.success(observation, false, 1L);
            }
        };
    }
}
