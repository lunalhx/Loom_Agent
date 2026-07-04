package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextCompactResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.context.ContextArtifactKind;
import cn.lunalhx.ai.domain.agent.service.context.ContextRecallTool;
import cn.lunalhx.ai.domain.agent.service.context.ContextWindowManager;
import cn.lunalhx.ai.domain.agent.service.context.DeepContextSummaryService;
import cn.lunalhx.ai.domain.agent.service.budget.DefaultBudgetGuard;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryContextArtifactRepository;
import cn.lunalhx.ai.infrastructure.context.InMemoryContextBlobStore;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryTraceRecorder;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * ContextWindowManager 独立契约（Phase 1 §5）。
 *
 * <p>直接测试 {@link ContextWindowManager}，覆盖：功能关闭不修改上下文、大型工具结果持久化为 artifact、
 * snip 保留 user_task 与最近记录、micro 仅压缩旧的且已有 artifact 的 tool result、自动 summary 在超 token
 * 目标时触发、reactive compact 保留配置数量最近记录、deep summary 成功用模型摘要 / 失败用 deterministic
 * fallback、compact result 元数据正确、artifact 只能被同一 root run 访问。
 *
 * <p>断言同时检查 {@link AgentContext} 最终状态、{@link ContextCompactResult} 元数据，
 * 以及 Repository/BlobStore 的持久化副作用。
 */
public class ContextWindowManagerContractTest {

    // ===== 1. 功能关闭 =====

    @Test
    public void disabledShouldNotModifyContextAndToolResult() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getContext().setEnabled(false);
        InMemoryContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        InMemoryContextBlobStore blobStore = new InMemoryContextBlobStore();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, blobStore);

        AgentContext context = contextForRoot("disabled-run");
        context.getDynamicText().appendUserTask("task");
        String big = "x".repeat(50000);
        ToolResult result = ToolResult.success(big, false, 1L);
        ToolResult prepared = manager.prepareToolResult(context, result);

        // 功能关闭：tool result 不被修改，observation 保持原样
        assertEquals(big, prepared.getObservation());
        assertFalse(prepared.isTruncated());
        assertNull(prepared.getArtifactId());
        // 没有 artifact 被持久化
        assertTrue(artifactRepository.listByRootRunId("disabled-run").isEmpty());

        // compactBeforePrompt 功能关闭返回 none，不修改上下文
        ContextCompactResult compact = manager.compactBeforePrompt(context);
        assertFalse(compact.isCompacted());
        assertEquals(1, context.getDynamicText().entries().size());
    }

    // ===== 2. 大型工具结果持久化为 artifact =====

    @Test
    public void largeToolResultShouldBePersistedAsArtifact() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getContext().setPersistToolResultChars(80);
        properties.getContext().setToolPreviewChars(30);
        InMemoryContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        InMemoryContextBlobStore blobStore = new InMemoryContextBlobStore();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, blobStore);

        AgentContext context = contextForRoot("artifact-run");
        String large = "alpha-" + "x".repeat(160) + "-omega-tail";
        ToolResult prepared = manager.prepareToolResult(context, ToolResult.success(large, false, 1L));

        // observation 被替换为 artifact 引用，原始内容不在 observation 中
        assertTrue(prepared.getObservation().contains("<persisted-output"));
        assertTrue(prepared.getObservation().contains("context_recall"));
        assertFalse(prepared.getObservation().contains("omega-tail"));
        assertTrue(prepared.isTruncated());
        assertNotNull(prepared.getArtifactId());
        assertEquals(ContextArtifactKind.TOOL_RESULT,
                artifactRepository.findByArtifactIdAndRootRunId(prepared.getArtifactId(), "artifact-run")
                        .orElseThrow().getKind());
        // blob store 保存了完整原始内容
        ContextArtifact saved = artifactRepository.listByRootRunId("artifact-run").get(0);
        assertTrue(blobStore.read(saved.getStorageUri()).contains("omega-tail"));
        // sha256 / originalChars / retainedChars 正确
        assertEquals(Integer.valueOf(large.length()), saved.getOriginalChars());
        assertNotNull(saved.getSha256());
    }

    @Test
    public void smallToolResultShouldNotBePersisted() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getContext().setPersistToolResultChars(1000);
        InMemoryContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, new InMemoryContextBlobStore());

        AgentContext context = contextForRoot("small-run");
        ToolResult prepared = manager.prepareToolResult(context, ToolResult.success("small", false, 1L));

        // 小于阈值：不持久化，observation 不变
        assertEquals("small", prepared.getObservation());
        assertFalse(prepared.isTruncated());
        assertTrue(artifactRepository.listByRootRunId("small-run").isEmpty());
    }

    // ===== 3. snip 保留 user_task 与最近记录 =====

    @Test
    public void snipShouldKeepUserTaskAndRecentEntries() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getBudget().setEstimatedCharsPerToken(1000); // 避免 summary 触发
        properties.getContext().setMaxDynamicEntries(3);
        InMemoryContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, new InMemoryContextBlobStore());

        AgentContext context = contextForRoot("snip-run");
        context.getDynamicText().appendUserTask("keep-this-task");
        for (int i = 1; i <= 6; i++) {
            context.getDynamicText().appendSystemNote(i, "test", "note-" + i, "content-" + i);
        }
        ContextCompactResult result = manager.compactBeforePrompt(context);

        assertTrue(result.isCompacted());
        assertTrue(result.getStrategies().contains("snip"));
        // user_task 被保留
        assertTrue(context.getDynamicText().entries().stream()
                .anyMatch(entry -> entry.getRole() == cn.lunalhx.ai.domain.agent.model.valobj.DynamicTextRole.USER_TASK));
        // 最近的记录被保留（note-6 应在保留范围内）
        assertTrue(context.getDynamicText().entries().stream()
                .anyMatch(entry -> "content-6".equals(entry.getContent())));
    }

    // ===== 4. micro 仅压缩旧的、已有 artifact 的 tool result =====

    @Test
    public void microShouldOnlyCompactOldArtifactBackedToolResults() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getBudget().setEstimatedCharsPerToken(1000); // 避免 summary
        properties.getContext().setKeepRecentToolResults(2);
        InMemoryContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        InMemoryContextBlobStore blobStore = new InMemoryContextBlobStore();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, blobStore);

        AgentContext context = contextForRoot("micro-run");
        // 3 个 tool result，前两个有 artifactId（旧），最后一个为最近（不压缩）
        context.getDynamicText().appendToolResult(1, "tool_dispatch", null,
                toolResultWithArtifact("old-1"), "old content 1");
        context.getDynamicText().appendToolResult(2, "tool_dispatch", null,
                toolResultWithArtifact("old-2"), "old content 2");
        context.getDynamicText().appendToolResult(3, "tool_dispatch", null, null, "recent content 3");
        ContextCompactResult result = manager.compactBeforePrompt(context);

        assertTrue(result.isCompacted());
        assertTrue(result.getStrategies().contains("micro"));
        // 旧的（有 artifact）被压缩为 persisted-output 引用；最近的保持原样
        List<cn.lunalhx.ai.domain.agent.model.entity.DynamicTextEntry> entries = context.getDynamicText().entries();
        assertTrue(entries.stream().anyMatch(entry -> entry.getContent() != null
                && entry.getContent().contains("<persisted-output")
                && Boolean.TRUE.equals(entry.getCompacted())));
        // 最近的未压缩
        assertTrue(entries.stream().anyMatch(entry -> "recent content 3".equals(entry.getContent())));
        // 没有 artifactId 的旧 tool result 不会被 micro 压缩（这里只有最近一个无 artifact，本就不在压缩范围）
    }

    // ===== 5. 自动 summary 在超 token 目标时触发 =====

    @Test
    public void autoSummaryShouldTriggerWhenOverTokenTarget() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getBudget().setEstimatedCharsPerToken(1);
        properties.getContext().setAutoCompactTokenLimit(100);
        properties.getContext().setMaxDynamicEntries(1000); // 避免 snip
        properties.getContext().setKeepRecentToolResults(2);
        InMemoryContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, new InMemoryContextBlobStore());

        AgentContext context = contextForRoot("summary-run");
        context.getDynamicText().appendUserTask("task");
        // 大量内容，超过 autoCompactTokenLimit
        for (int i = 1; i <= 50; i++) {
            context.getDynamicText().appendSystemNote(i, "test", "note-" + i, "x".repeat(20));
        }
        ContextCompactResult result = manager.compactBeforePrompt(context);

        assertTrue(result.isCompacted());
        assertTrue("应触发 summary 策略，实际=" + result.getStrategies(),
                result.getStrategies().contains("summary"));
        // summary 产生 transcript artifact
        assertNotNull(result.getTranscriptArtifactId());
        assertFalse(artifactRepository.listByRootRunId("summary-run").isEmpty());
    }

    // ===== 6. reactive compact 保留配置数量最近记录 =====

    @Test
    public void reactiveCompactShouldKeepConfiguredRecentEntries() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getBudget().setEstimatedCharsPerToken(1);
        properties.getContext().setReactiveKeepRecentEntries(5);
        InMemoryContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, new InMemoryContextBlobStore());

        AgentContext context = contextForRoot("reactive-run");
        context.getDynamicText().appendUserTask("original task");
        for (int i = 1; i <= 7; i++) {
            context.getDynamicText().appendSystemNote(i, "test", "entry-" + i, "entry-" + i);
        }
        ContextCompactResult result = manager.reactiveCompact(context, 10);

        // 保留 5 条最近记录（reactive_summary 策略）
        assertEquals(5, result.getRetainedEntryCount());
        assertTrue(result.getStrategies().contains("reactive_summary"));
        assertNotNull(result.getTranscriptArtifactId());
        // 上下文：user_task + system note(summary) + 5 recent = 7 条
        assertEquals(7, context.getDynamicText().entries().size());
        // 最近 5 条按顺序保留 entry-3..entry-7
        assertEquals("entry-7", context.getDynamicText().entries().get(6).getContent());
    }

    // ===== 7. deep summary 成功用模型摘要 =====

    @Test
    public void deepSummarySuccessShouldUseModelSummary() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getBudget().setEstimatedCharsPerToken(1);
        properties.getContext().setDeepSummaryChunkTokenLimit(10000);
        properties.getContext().setDeepSummaryMaxCalls(8);
        AtomicInteger calls = new AtomicInteger();
        DeepContextSummaryService deepService = new DeepContextSummaryService(
                new ModelGateway() {
                    @Override
                    public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                        return Flux.empty();
                    }

                    @Override
                    public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                        calls.incrementAndGet();
                        return Mono.just(ModelChatResult.builder()
                                .content("MODEL_SUMMARY")
                                .finishReason("stop")
                                .build());
                    }
                },
                properties,
                new DefaultBudgetGuard(properties),
                new InMemoryTraceRecorder());
        InMemoryContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, new InMemoryContextBlobStore(), deepService);

        AgentContext context = contextForRoot("deep-ok-run");
        context.getDynamicText().appendUserTask("task");
        context.getDynamicText().appendSystemNote(1, "test", "n", "x".repeat(40));
        ContextCompactResult result = manager.deepSummaryCompact(context, 10, System.currentTimeMillis() + 5000L);

        assertTrue(result.isCompacted());
        assertEquals("deep_summary", result.getStrategies().get(0));
        assertTrue("deep summary 应调用模型", calls.get() >= 1);
        // 上下文中应包含模型摘要文本
        assertTrue(context.getDynamicText().render().contains("MODEL_SUMMARY"));
    }

    // ===== 8. deep summary 失败用 deterministic fallback =====

    @Test
    public void deepSummaryFailureShouldUseDeterministicFallback() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getBudget().setEstimatedCharsPerToken(1);
        DeepContextSummaryService deepService = new DeepContextSummaryService(
                new ModelGateway() {
                    @Override
                    public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                        return Flux.empty();
                    }

                    @Override
                    public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                        throw new RuntimeException("model unavailable");
                    }
                },
                properties,
                new DefaultBudgetGuard(properties),
                new InMemoryTraceRecorder());
        InMemoryContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, new InMemoryContextBlobStore(), deepService);

        AgentContext context = contextForRoot("deep-fail-run");
        context.getDynamicText().appendUserTask("user goal text");
        context.getDynamicText().appendSystemNote(1, "test", "n", "x".repeat(40));
        ContextCompactResult result = manager.deepSummaryCompact(context, 10, System.currentTimeMillis() + 5000L);

        assertTrue(result.isCompacted());
        // 失败降级为 deterministic
        assertEquals("deep_summary_deterministic", result.getStrategies().get(0));
        // deterministic fallback 摘要包含 UserGoal
        assertTrue(context.getDynamicText().render().contains("UserGoal"));
    }

    // ===== 9. compact result 元数据正确 =====

    @Test
    public void compactResultMetadataShouldBeCorrect() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getBudget().setEstimatedCharsPerToken(1);
        properties.getContext().setReactiveKeepRecentEntries(3);
        InMemoryContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, new InMemoryContextBlobStore());

        AgentContext context = contextForRoot("metadata-run");
        context.getDynamicText().appendUserTask("task");
        for (int i = 1; i <= 5; i++) {
            context.getDynamicText().appendSystemNote(i, "test", "entry-" + i, "entry-" + i);
        }
        int target = 10;
        ContextCompactResult result = manager.reactiveCompact(context, target);

        // 元数据：策略 / targetTokens / retainedEntryCount / transcriptArtifactId / compacted
        assertEquals(List.of("reactive_summary"), result.getStrategies());
        assertEquals(target, result.getTargetTokens());
        assertEquals(3, result.getRetainedEntryCount());
        assertTrue(result.isCompacted());
        assertNotNull(result.getTranscriptArtifactId());
        // before/after estimatedTokens 合法（均为正数，after 基于 target 计算的 fitsTarget 标志一致）
        assertTrue(result.getBeforeEstimatedTokens() > 0);
        assertTrue(result.getAfterEstimatedTokens() > 0);
        assertEquals(result.getAfterEstimatedTokens() <= target, result.isFitsTarget());
        // artifact count >= 1（transcript artifact）
        assertTrue(result.getArtifactCount() >= 1);
    }

    // ===== 10. artifact 只能被同一 root run 访问 =====

    @Test
    public void artifactShouldOnlyBeAccessibleBySameRootRun() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getContext().setPersistToolResultChars(10);
        InMemoryContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        InMemoryContextBlobStore blobStore = new InMemoryContextBlobStore();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, blobStore);

        AgentContext runA = contextForRoot("root-a");
        AgentContext runB = contextForRoot("root-b");
        manager.prepareToolResult(runA, ToolResult.success("A-" + "a".repeat(40), false, 1L));
        ToolResult bResult = manager.prepareToolResult(runB, ToolResult.success("B-" + "b".repeat(40), false, 1L));

        // A 的 artifact 列表里只有 A，没有 B
        List<ContextArtifact> aArtifacts = artifactRepository.listByRootRunId("root-a");
        assertTrue(aArtifacts.stream().allMatch(a -> "root-a".equals(a.getRootRunId())));
        // 通过 artifactId + rootRunId 跨 root 访问被拒
        assertFalse(artifactRepository.findByArtifactIdAndRootRunId(bResult.getArtifactId(), "root-a").isPresent());

        // ContextRecallTool 跨 root run 调用返回 context_recall_not_found
        ContextRecallTool recallTool = new ContextRecallTool(artifactRepository, blobStore);
        ObjectNode input = new ObjectMapper().createObjectNode()
                .put("action", "get")
                .put("artifactId", bResult.getArtifactId());
        ToolResult denied = recallTool.call(ToolCall.builder()
                .name(ContextRecallTool.NAME)
                .input(input)
                .runId("root-a")
                .rootRunId("root-a")
                .conversationId("conversation-a")
                .build());
        assertFalse(denied.isSuccess());
        assertEquals("context_recall_not_found", denied.getErrorCode());

        // 同 root run 访问成功
        ToolResult allowed = recallTool.call(ToolCall.builder()
                .name(ContextRecallTool.NAME)
                .input(new ObjectMapper().createObjectNode()
                        .put("action", "get")
                        .put("artifactId", bResult.getArtifactId()))
                .runId("root-b")
                .rootRunId("root-b")
                .conversationId("conversation-b")
                .build());
        assertTrue(allowed.isSuccess());
    }

    // ===== 11. persisted-output 字段完整性 =====

    @Test
    public void persistedOutputTagContainsAllFields() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getContext().setPersistToolResultChars(50);
        properties.getContext().setToolPreviewChars(20);
        InMemoryContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        InMemoryContextBlobStore blobStore = new InMemoryContextBlobStore();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, blobStore);

        AgentContext context = contextForRoot("fields-run");
        String large = "ABCDEFGHIJ-" + "y".repeat(100);
        ToolResult prepared = manager.prepareToolResult(context, ToolResult.success(large, false, 1L));

        String obs = prepared.getObservation();
        assertTrue(obs.contains("<persisted-output"));
        assertTrue(obs.contains("artifactId=\""));
        assertTrue(obs.contains("kind=\"TOOL_RESULT\""));
        assertTrue(obs.contains("originalChars=\""));
        assertTrue(obs.contains("retainedChars=\""));
        assertTrue(obs.contains("sha256=\""));
        assertTrue(obs.contains(" />"));
        assertTrue(obs.contains("<persisted_content>"));
        assertTrue(obs.contains("</persisted_content>"));
        assertTrue(obs.contains("context_recall(action=get, artifactId="));
    }

    // ===== 12. 预览是纯前缀，无省略号 =====

    @Test
    public void previewIsPurePrefixWithoutEllipsis() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getContext().setPersistToolResultChars(50);
        properties.getContext().setToolPreviewChars(20);
        InMemoryContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        InMemoryContextBlobStore blobStore = new InMemoryContextBlobStore();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, blobStore);

        AgentContext context = contextForRoot("prefix-run");
        String large = "ABCDEFGHIJ-" + "y".repeat(100);
        ToolResult prepared = manager.prepareToolResult(context, ToolResult.success(large, false, 1L));

        String obs = prepared.getObservation();
        // 预览中不应包含省略号 "..."
        int contentStart = obs.indexOf("<persisted_content>");
        int contentEnd = obs.indexOf("</persisted_content>");
        String previewContent = obs.substring(contentStart + "<persisted_content>\n".length(), contentEnd - 1);
        assertFalse("预览不应包含省略号 ...", previewContent.contains("..."));
    }

    // ===== 13. Unicode 代理对边界 =====

    @Test
    public void previewDoesNotSplitSurrogatePairs() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getContext().setPersistToolResultChars(100);
        // 设置截断点为 11，正好在高代理字符位置（emoji 占据 index 10-11）
        properties.getContext().setToolPreviewChars(11);
        InMemoryContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        InMemoryContextBlobStore blobStore = new InMemoryContextBlobStore();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, blobStore);

        AgentContext context = contextForRoot("unicode-run");
        // 10 个 ASCII + 1 个 emoji（2 个 char） = 12 个 char，截断到 11 会切到高代理
        String prefix = "0123456789";  // 10 chars
        String emoji = "😀"; // 😀 = 2 chars (surrogate pair)
        String large = prefix + emoji + "-" + "z".repeat(200);
        ToolResult prepared = manager.prepareToolResult(context, ToolResult.success(large, false, 1L));

        String obs = prepared.getObservation();
        int contentStart = obs.indexOf("<persisted_content>");
        int contentEnd = obs.indexOf("</persisted_content>");
        String previewContent = obs.substring(contentStart + "<persisted_content>\n".length(), contentEnd - 1);

        // 预览回退到安全边界（10），不拆分 surrogate pair
        assertEquals(10, previewContent.length());
        assertEquals(prefix, previewContent);
    }

    // ===== 14. 2000 字符硬上限 =====

    @Test
    public void previewCappedAt2000() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getContext().setPersistToolResultChars(3000);
        // 配置超过 2000，但预览不应超过 2000
        properties.getContext().setToolPreviewChars(2500);
        InMemoryContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        InMemoryContextBlobStore blobStore = new InMemoryContextBlobStore();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, blobStore);

        AgentContext context = contextForRoot("cap-run");
        String large = "A-" + "x".repeat(5000);
        ToolResult prepared = manager.prepareToolResult(context, ToolResult.success(large, false, 1L));

        String obs = prepared.getObservation();
        int contentStart = obs.indexOf("<persisted_content>");
        int contentEnd = obs.indexOf("</persisted_content>");
        String previewContent = obs.substring(contentStart + "<persisted_content>\n".length(), contentEnd - 1);

        assertTrue("预览不应超过 2000，实际=" + previewContent.length(),
                previewContent.length() <= 2000);
        ContextArtifact saved = artifactRepository.listByRootRunId("cap-run").get(0);
        assertTrue("retainedChars 不应超过 2000，实际=" + saved.getRetainedChars(),
                saved.getRetainedChars() <= 2000);
    }

    // ===== 15. 预览不超过原始内容长度 =====

    @Test
    public void previewCappedByContentLength() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getContext().setPersistToolResultChars(30);
        properties.getContext().setToolPreviewChars(2000);
        InMemoryContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        InMemoryContextBlobStore blobStore = new InMemoryContextBlobStore();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, blobStore);

        AgentContext context = contextForRoot("short-run");
        // content 虽然超过阈值但较短（35 chars），预览不能超过实际长度
        String large = "A-" + "x".repeat(33);  // 35 chars total
        assertTrue(large.length() > 30);  // 超过阈值
        ToolResult prepared = manager.prepareToolResult(context, ToolResult.success(large, false, 1L));

        String obs = prepared.getObservation();
        int contentStart = obs.indexOf("<persisted_content>");
        int contentEnd = obs.indexOf("</persisted_content>");
        String previewContent = obs.substring(contentStart + "<persisted_content>\n".length(), contentEnd - 1);

        // 预览为完整内容前缀（实际 35 字符，但会被 persistToolResultChars 限制在 30）
        assertTrue("预览不应超过 persistToolResultChars(30)，实际=" + previewContent.length(),
                previewContent.length() <= 30);
        // 因为是前缀，无省略号
        assertFalse(previewContent.contains("..."));
    }

    // ===== 16. 小预览配置 =====

    @Test
    public void smallPreviewConfig() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getContext().setPersistToolResultChars(50);
        properties.getContext().setToolPreviewChars(5);
        InMemoryContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        InMemoryContextBlobStore blobStore = new InMemoryContextBlobStore();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, blobStore);

        AgentContext context = contextForRoot("small-preview-run");
        String large = "ABCDEFGHIJ-" + "z".repeat(200);
        ToolResult prepared = manager.prepareToolResult(context, ToolResult.success(large, false, 1L));

        String obs = prepared.getObservation();
        int contentStart = obs.indexOf("<persisted_content>");
        int contentEnd = obs.indexOf("</persisted_content>");
        String previewContent = obs.substring(contentStart + "<persisted_content>\n".length(), contentEnd - 1);

        assertEquals(5, previewContent.length());
        assertEquals("ABCDE", previewContent);
    }

    // ===== 17. 多行内容预览保留换行 =====

    @Test
    public void multiLineContentInPreview() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getContext().setPersistToolResultChars(60);
        properties.getContext().setToolPreviewChars(40);
        InMemoryContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        InMemoryContextBlobStore blobStore = new InMemoryContextBlobStore();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, blobStore);

        AgentContext context = contextForRoot("multiline-run");
        String large = "line1\nline2\nline3\n" + "z".repeat(200);
        ToolResult prepared = manager.prepareToolResult(context, ToolResult.success(large, false, 1L));

        String obs = prepared.getObservation();
        int contentStart = obs.indexOf("<persisted_content>");
        int contentEnd = obs.indexOf("</persisted_content>");
        String previewContent = obs.substring(contentStart + "<persisted_content>\n".length(), contentEnd - 1);

        // 预览应保留换行符
        assertTrue(previewContent.contains("\n"));
        // 预览是原内容的纯前缀
        assertTrue(large.startsWith(previewContent));
    }

    // ===== 18. 压缩条目使用统一 persisted-output 标签 =====

    @Test
    public void compactedEntriesUseUnifiedTag() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getBudget().setEstimatedCharsPerToken(1000);
        properties.getContext().setKeepRecentToolResults(1);
        InMemoryContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        InMemoryContextBlobStore blobStore = new InMemoryContextBlobStore();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, blobStore);

        AgentContext context = contextForRoot("unified-compact-run");
        context.getDynamicText().appendToolResult(1, "tool_dispatch", null,
                toolResultWithArtifact("art-1"), "old content 1");
        context.getDynamicText().appendToolResult(2, "tool_dispatch", null,
                toolResultWithArtifact("art-2"), "old content 2");
        context.getDynamicText().appendToolResult(3, "tool_dispatch", null, null, "recent content 3");
        manager.compactBeforePrompt(context);

        List<cn.lunalhx.ai.domain.agent.model.entity.DynamicTextEntry> entries = context.getDynamicText().entries();
        // 压缩条目使用 persisted-output，不含旧标签
        assertTrue(entries.stream().anyMatch(entry -> entry.getContent() != null
                && entry.getContent().contains("<persisted-output")));
        assertFalse("不应包含旧 [compacted_tool_result] 标签",
                entries.stream().anyMatch(entry -> entry.getContent() != null
                        && entry.getContent().contains("[compacted_tool_result]")));
    }

    // ===== 19. Observation 不含旧标签 =====

    @Test
    public void observationDoesNotContainOldTags() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getContext().setPersistToolResultChars(50);
        properties.getContext().setToolPreviewChars(20);
        InMemoryContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        InMemoryContextBlobStore blobStore = new InMemoryContextBlobStore();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, blobStore);

        AgentContext context = contextForRoot("no-old-tags-run");
        String large = "A-" + "y".repeat(200);
        ToolResult prepared = manager.prepareToolResult(context, ToolResult.success(large, false, 1L));

        String obs = prepared.getObservation();
        assertFalse("不应包含 [context_artifact]", obs.contains("[context_artifact]"));
        assertFalse("不应包含 [compacted_tool_result]", obs.contains("[compacted_tool_result]"));
    }

    // ===== 20. 旧格式后向兼容：不迁移仍可构造提示词 =====

    @Test
    public void backwardCompatOldArtifactFormatInLedgerDoesNotBreak() {
        // 模拟旧格式 [context_artifact] 的 artifact 记录仍可通过 context_recall 取回
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        InMemoryContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        InMemoryContextBlobStore blobStore = new InMemoryContextBlobStore();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, blobStore);

        AgentContext context = contextForRoot("backward-compat");
        // 手动插入一个记录，模拟旧格式场景
        String artifactId = "ctx-old-format";
        String fullContent = "historical data that was stored with old format";
        blobStore.write("backward-compat", artifactId, fullContent);
        ContextArtifact oldArtifact = ContextArtifact.builder()
                .artifactId(artifactId)
                .runId("backward-compat")
                .rootRunId("backward-compat")
                .kind(ContextArtifactKind.TOOL_RESULT)
                .storageUri("memory://backward-compat/" + artifactId)
                .preview("historical dat")
                .sha256(org.apache.commons.codec.digest.DigestUtils.sha256Hex(fullContent))
                .originalChars(fullContent.length())
                .retainedChars(14)
                .createdAt(java.time.Instant.now())
                .build();
        artifactRepository.save(oldArtifact);

        // context_recall 仍能正常取回
        ContextRecallTool recallTool = new ContextRecallTool(artifactRepository, blobStore);
        ObjectNode input = new ObjectMapper().createObjectNode()
                .put("action", "get")
                .put("artifactId", artifactId);
        ToolResult recall = recallTool.call(ToolCall.builder()
                .name(ContextRecallTool.NAME)
                .input(input)
                .runId("backward-compat")
                .rootRunId("backward-compat")
                .conversationId("conv-backward")
                .build());
        assertTrue("旧格式 artifact 应仍可通过 context_recall 取回", recall.isSuccess());
        assertTrue(recall.getObservation().contains("historical data"));
    }

    // ===== 辅助 =====

    private AgentContext contextForRoot(String rootRunId) {
        AgentContext context = new AgentContext();
        context.setRunId(rootRunId);
        context.setRootRunId(rootRunId);
        context.setRequestId("request-" + rootRunId);
        context.setConversationId("conversation-" + rootRunId);
        context.setQuestion("question-" + rootRunId);
        context.setMaxSteps(3);
        context.setStartedAt(Instant.now());
        context.setToolSpecs(List.of());
        return context;
    }

    private ToolResult toolResultWithArtifact(String artifactId) {
        ToolResult result = ToolResult.success("backed", false, 1L);
        result.setArtifactId(artifactId);
        result.setOriginalChars(100);
        return result;
    }
}
