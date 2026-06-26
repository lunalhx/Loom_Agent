package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.flow.node.RenderPromptNode;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentPlan;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.ContextWindowManager;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryContextArtifactRepository;
import cn.lunalhx.ai.infrastructure.context.InMemoryContextBlobStore;
import org.junit.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RenderPromptNodeTest {

    private RenderPromptNode newNode() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getContext().setEnabled(false);
        ContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        ContextBlobStore blobStore = new InMemoryContextBlobStore();
        ContextWindowManager cwm = new ContextWindowManager(properties, artifactRepository, blobStore);
        return new RenderPromptNode(cwm);
    }

    private AgentContext basicContext() {
        AgentContext context = new AgentContext();
        context.setRunId("cache-run");
        context.setRootRunId("cache-run");
        context.setRequestId("req-1");
        context.setConversationId("conv-1");
        context.setQuestion("test question");
        context.setMaxSteps(5);
        context.setStep(0);
        context.setStartedAt(Instant.now());
        context.setToolSpecs(List.of(
                new ToolSpec("read", "Read a file", "{\"path\":\"string\"}"),
                new ToolSpec("write", "Write a file", "{\"path\":\"string\",\"content\":\"string\"}")
        ));
        return context;
    }

    // ===== 1. cache hit: 同一 AgentContext 连续 render 两次，第二次 currentPrompt 应与第一次为同一 String 引用 =====

    @Test
    public void sameContextShouldReturnSamePromptReference() {
        RenderPromptNode node = newNode();
        AgentContext context = basicContext();
        context.getDynamicText().appendUserTask("test question");

        NodeResult r1 = node.apply(context);
        String prompt1 = context.getCurrentPrompt();
        assertNotNull(prompt1);

        NodeResult r2 = node.apply(context);
        String prompt2 = context.getCurrentPrompt();
        assertNotNull(prompt2);

        assertSame("同一 context 无变化时应返回同一 String 引用", prompt1, prompt2);
    }

    // ===== 2. cache invalidation on dynamicText change: 追加 system note 后重新 render，断言生成新 prompt 且包含新内容 =====

    @Test
    public void dynamicTextChangeShouldInvalidateCache() {
        RenderPromptNode node = newNode();
        AgentContext context = basicContext();
        context.getDynamicText().appendUserTask("test question");

        node.apply(context);
        String prompt1 = context.getCurrentPrompt();
        String cacheKey1 = context.getPromptRenderCacheKey();
        assertNotNull(cacheKey1);

        context.getDynamicText().appendSystemNote(1, "parse_retry", "Parse Error", "parse_error occurred");

        node.apply(context);
        String prompt2 = context.getCurrentPrompt();
        String cacheKey2 = context.getPromptRenderCacheKey();

        assertNotEquals("追加 system note 后应重新渲染", prompt1, prompt2);
        assertNotEquals("追加 system note 后 cache key 应变化", cacheKey1, cacheKey2);
        assertTrue("新 prompt 应包含新增的 system note 内容",
                prompt2.contains("parse_error occurred"));
    }

    // ===== 3. cache invalidation on plan change =====

    @Test
    public void planChangeShouldInvalidateCache() {
        RenderPromptNode node = newNode();
        AgentContext context = basicContext();
        context.getDynamicText().appendUserTask("test question");
        AgentPlan plan = AgentPlan.forQuestion("initial plan");
        context.setPlan(plan);

        node.apply(context);
        String prompt1 = context.getCurrentPrompt();
        String cacheKey1 = context.getPromptRenderCacheKey();

        plan.applyTodoWrite(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .set("todos", new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode()
                        .add(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                                .put("content", "updated task")
                                .put("status", "completed"))));

        node.apply(context);
        String prompt2 = context.getCurrentPrompt();
        String cacheKey2 = context.getPromptRenderCacheKey();

        assertNotEquals("plan 版本变化后应重新渲染", prompt1, prompt2);
        assertNotEquals("plan 版本变化后 cache key 应变化", cacheKey1, cacheKey2);
    }

    // ===== 4. cache invalidation on question change =====

    @Test
    public void questionChangeShouldInvalidateCache() {
        RenderPromptNode node = newNode();
        AgentContext context = basicContext();
        context.getDynamicText().appendUserTask("old question");

        node.apply(context);
        String prompt1 = context.getCurrentPrompt();

        context.setQuestion("new question");
        node.apply(context);
        String prompt2 = context.getCurrentPrompt();

        assertNotEquals("question 变化后应重新渲染", prompt1, prompt2);
        assertTrue("新 prompt 应包含新 question", prompt2.contains("new question"));
    }

    // ===== 5. cache invalidation on toolSpecs change =====

    @Test
    public void toolSpecsChangeShouldInvalidateCache() {
        RenderPromptNode node = newNode();
        AgentContext context = basicContext();
        context.getDynamicText().appendUserTask("test question");

        node.apply(context);
        String prompt1 = context.getCurrentPrompt();

        context.setToolSpecs(List.of(
                new ToolSpec("read", "Read a file", "{\"path\":\"string\"}"),
                new ToolSpec("write", "Write a file", "{\"path\":\"string\",\"content\":\"string\"}"),
                new ToolSpec("delete", "Delete a file", "{\"path\":\"string\"}")
        ));
        node.apply(context);
        String prompt2 = context.getCurrentPrompt();

        assertNotEquals("toolSpecs 变化后应重新渲染", prompt1, prompt2);
        assertTrue("新 prompt 应包含新增的 delete 工具", prompt2.contains("delete"));
    }

    // ===== 6. empty dynamicText: cache should still work =====

    @Test
    public void emptyDynamicTextShouldStillCache() {
        RenderPromptNode node = newNode();
        AgentContext context = basicContext();

        node.apply(context);
        String prompt1 = context.getCurrentPrompt();
        assertNotNull(prompt1);

        node.apply(context);
        String prompt2 = context.getCurrentPrompt();

        assertSame("empty dynamicText 时缓存应生效", prompt1, prompt2);
    }

    // ===== 7. null currentPrompt: cache miss 返回新 prompt =====

    @Test
    public void nullCurrentPromptShouldRenderNewPrompt() {
        RenderPromptNode node = newNode();
        AgentContext context = basicContext();
        context.getDynamicText().appendUserTask("test question");
        assertNull(context.getCurrentPrompt());

        node.apply(context);
        assertNotNull(context.getCurrentPrompt());
        assertNotNull(context.getPromptRenderCacheKey());
    }

    // ===== 8. plan identityHashCode change: different plan object should invalidate =====

    @Test
    public void differentPlanObjectShouldInvalidateCache() {
        RenderPromptNode node = newNode();
        AgentContext context = basicContext();
        context.getDynamicText().appendUserTask("test question");
        context.setPlan(AgentPlan.forQuestion("plan A"));

        node.apply(context);
        String prompt1 = context.getCurrentPrompt();
        String cacheKey1 = context.getPromptRenderCacheKey();

        AgentPlan newPlan = AgentPlan.forQuestion("缓存 cache plan B");
        context.setPlan(newPlan);

        node.apply(context);
        String prompt2 = context.getCurrentPrompt();
        String cacheKey2 = context.getPromptRenderCacheKey();

        assertNotEquals("不同 plan 对象 cache key 应变化", cacheKey1, cacheKey2);
        assertTrue("新 prompt 应包含新 plan 的缓存相关 task",
                prompt2.contains("理解目标模块现有实现和调用路径"));
        assertTrue("新 prompt 不应包含旧 plan 的通用 task",
                prompt1.contains("理解用户任务和相关代码上下文"));
    }

    // ===== 9. snapshot: promptRenderCacheKey 不参与 AgentContextSnapshot.from(...).restore() =====

    @Test
    public void promptRenderCacheKeyShouldNotSurviveSnapshotRoundtrip() {
        RenderPromptNode node = newNode();
        AgentContext context = basicContext();
        context.getDynamicText().appendUserTask("test question");
        context.getDynamicText().appendSystemNote(1, "test", "Note", "some note");
        context.setPlan(AgentPlan.forQuestion("plan"));
        context.setCurrentPrompt("fake prompt for snapshot");

        AgentContextSnapshot snapshot = AgentContextSnapshot.from(context);
        AgentContext restored = snapshot.restore();

        assertNull("snapshot restore 后 promptRenderCacheKey 应为 null（不参与序列化）",
                restored.getPromptRenderCacheKey());
    }

    // ===== 10. micro compaction should bump DynamicText version =====

    @Test
    public void microCompactionShouldBumpDynamicTextVersion() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getBudget().setEstimatedCharsPerToken(1000);
        properties.getContext().setKeepRecentToolResults(1);
        InMemoryContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        InMemoryContextBlobStore blobStore = new InMemoryContextBlobStore();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, blobStore);

        AgentContext context = basicContext();
        context.getDynamicText().appendUserTask("task");

        cn.lunalhx.ai.domain.tool.model.ToolResult tr1 =
                cn.lunalhx.ai.domain.tool.model.ToolResult.success("old", false, 1L);
        tr1.setArtifactId("artifact-1");
        tr1.setOriginalChars(100);
        context.getDynamicText().appendToolResult(1, "dispatch", null, tr1, "old content");

        cn.lunalhx.ai.domain.tool.model.ToolResult tr2 =
                cn.lunalhx.ai.domain.tool.model.ToolResult.success("recent", false, 1L);
        tr2.setArtifactId("artifact-2");
        tr2.setOriginalChars(100);
        context.getDynamicText().appendToolResult(2, "dispatch", null, tr2, "recent content");

        int versionBefore = context.getDynamicText().getVersion();

        manager.compactBeforePrompt(context);

        int versionAfter = context.getDynamicText().getVersion();

        assertTrue("micro compaction 后 DynamicText.version 应递增",
                versionAfter > versionBefore);
    }
}
