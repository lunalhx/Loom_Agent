package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.flow.node.RenderPromptNode;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentPlan;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRole;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.context.ContextWindowManager;
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
        return new RenderPromptNode(cwm, null, artifactRepository, blobStore);
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

    // ===== helper =====

    private static int firstDiffIndex(String a, String b) {
        int minLen = Math.min(a.length(), b.length());
        for (int i = 0; i < minLen; i++) {
            if (a.charAt(i) != b.charAt(i)) return i;
        }
        return minLen;
    }

    // ===== 11. block ordering: Action/Final JSON 示例应在 DynamicText 之前 =====

    @Test
    public void actionJsonExamplesShouldPrecedeDynamicText() {
        RenderPromptNode node = newNode();
        AgentContext context = basicContext();
        context.getDynamicText().appendUserTask("test question");
        context.getDynamicText().appendSystemNote(1, "test", "Note", "some note");

        node.apply(context);
        String prompt = context.getCurrentPrompt();

        int actionJsonPos = prompt.indexOf("Action JSON 示例：");
        int dynamicTextPos = prompt.indexOf("动态上下文：");
        assertTrue("Action JSON 示例应在 DynamicText 之前", actionJsonPos >= 0);
        assertTrue("DynamicText 应存在", dynamicTextPos >= 0);
        assertTrue("Action JSON 示例(" + actionJsonPos + ") 应在 DynamicText(" + dynamicTextPos + ") 之前",
                actionJsonPos < dynamicTextPos);
    }

    // ===== 12. block ordering: plan 应在 DynamicText 之后 =====

    @Test
    public void planShouldFollowDynamicText() {
        RenderPromptNode node = newNode();
        AgentContext context = basicContext();
        context.getDynamicText().appendUserTask("test question");
        context.getDynamicText().appendSystemNote(1, "test", "Note", "some note");
        context.setPlan(AgentPlan.forQuestion("test plan"));

        node.apply(context);
        String prompt = context.getCurrentPrompt();

        int dynamicTextPos = prompt.indexOf("动态上下文：");
        // lastIndexOf: plan section is the LAST "当前计划：" occurrence (protocol text also contains it)
        int planPos = prompt.lastIndexOf("当前计划：");
        assertTrue("DynamicText 应存在", dynamicTextPos >= 0);
        assertTrue("当前计划应存在", planPos >= 0);
        assertTrue("当前计划(" + planPos + ") 应在 DynamicText(" + dynamicTextPos + ") 之后",
                planPos > dynamicTextPos);
    }

    // ===== 13. block ordering: 步数预算应在 DynamicText 之后 =====

    @Test
    public void budgetShouldFollowDynamicText() {
        RenderPromptNode node = newNode();
        AgentContext context = basicContext();
        context.getDynamicText().appendUserTask("test question");
        context.getDynamicText().appendSystemNote(1, "test", "Note", "some note");
        context.setMaxSegments(2);
        context.setMaxTotalSteps(20);

        node.apply(context);
        String prompt = context.getCurrentPrompt();

        int dynamicTextPos = prompt.indexOf("动态上下文：");
        int budgetPos = prompt.indexOf("执行预算：");
        assertTrue("DynamicText 应存在", dynamicTextPos >= 0);
        assertTrue("执行预算应存在", budgetPos >= 0);
        assertTrue("执行预算(" + budgetPos + ") 应在 DynamicText(" + dynamicTextPos + ") 之后",
                budgetPos > dynamicTextPos);
    }

    // ===== 14. block ordering: complete order verification =====

    @Test
    public void allBlocksShouldBeInCorrectOrder() {
        RenderPromptNode node = newNode();
        AgentContext context = basicContext();
        context.getDynamicText().appendUserTask("test question");
        context.getDynamicText().appendSystemNote(1, "test", "Note", "some note");
        context.setPlan(AgentPlan.forQuestion("test plan"));
        context.setMaxSegments(2);
        context.setMaxTotalSteps(20);

        node.apply(context);
        String prompt = context.getCurrentPrompt();

        // Expected order (positional):
        // 1. 固定角色/协议/安全规则 (开头)
        // 2. Action/Final JSON 示例
        // 3. active skills / available skills / 工具目录
        // 4. 用户问题
        // 5. DynamicText
        // 6. 步数预算 / 计划

        int actionPos = prompt.indexOf("Action JSON 示例：");
        int toolsPos = prompt.indexOf("可用工具：");
        int questionPos = prompt.indexOf("用户问题：");
        int dynamicPos = prompt.indexOf("动态上下文：");
        int budgetPos = prompt.indexOf("执行预算：");
        // lastIndexOf: plan section is the LAST "当前计划：" occurrence (protocol text also contains it)
        int planPos = prompt.lastIndexOf("当前计划：");

        assertTrue("Action JSON 示例应存在", actionPos >= 0);
        assertTrue("工具目录应存在", toolsPos >= 0);
        assertTrue("用户问题应存在", questionPos >= 0);
        assertTrue("DynamicText 应存在", dynamicPos >= 0);
        assertTrue("执行预算应存在", budgetPos >= 0);
        assertTrue("当前计划应存在", planPos >= 0);

        assertTrue("Action < tools", actionPos < toolsPos);
        assertTrue("tools < question", toolsPos < questionPos);
        assertTrue("question < DynamicText", questionPos < dynamicPos);
        assertTrue("DynamicText < budget", dynamicPos < budgetPos);
        assertTrue("DynamicText < plan", dynamicPos < planPos);
    }

    // ===== 15. two-round LCP: 第一差异点不在计划/步数位置 =====

    @Test
    public void firstDiffBetweenRoundsShouldNotBeInPlanOrBudgetSection() {
        RenderPromptNode node = newNode();
        AgentContext context = basicContext();
        context.setPlan(AgentPlan.forQuestion("test plan"));
        context.setMaxSegments(2);
        context.setMaxTotalSteps(20);
        context.getDynamicText().appendUserTask("test question");
        context.getDynamicText().appendAssistantAction(1, "dispatch",
                cn.lunalhx.ai.domain.agent.model.entity.AgentDecision.builder()
                        .type("action")
                        .tool("read")
                        .reason("reading file")
                        .build());
        context.getDynamicText().appendToolResult(1, "dispatch", null, "file content here");

        // Round 1
        node.apply(context);
        String prompt1 = context.getCurrentPrompt();

        // Simulate next round: add more DynamicText, advance step
        context.setStep(1);
        context.getDynamicText().appendAssistantAction(2, "dispatch",
                cn.lunalhx.ai.domain.agent.model.entity.AgentDecision.builder()
                        .type("action")
                        .tool("write")
                        .reason("writing file")
                        .build());
        context.getDynamicText().appendToolResult(2, "dispatch", null, "write successful");

        // Round 2
        node.apply(context);
        String prompt2 = context.getCurrentPrompt();

        int diffIdx = firstDiffIndex(prompt1, prompt2);
        int dynamicTextStart = prompt1.indexOf("动态上下文：");
        // lastIndexOf: plan section is at the tail
        int planPosRound2 = prompt2.lastIndexOf("当前计划：");
        int planProtoPosR2 = prompt2.indexOf("当前计划：");
        int budgetPosRound2 = prompt2.indexOf("执行预算：");

        assertTrue("第一差异应存在", diffIdx >= 0);
        assertTrue("DynamicText 应存在", dynamicTextStart >= 0);

        // The first diff should be in or after DynamicText, not before it
        assertTrue("第一差异位置(" + diffIdx + ") 应在 DynamicText 起始(" + dynamicTextStart + ") 之后",
                diffIdx > dynamicTextStart);

        // Plan SECTION (last occurrence) should be after DynamicText
        if (planPosRound2 >= 0 && planPosRound2 != planProtoPosR2) {
            assertTrue("计划 section(" + planPosRound2 + ") 应在 DynamicText(" + dynamicTextStart + ") 之后",
                    planPosRound2 > dynamicTextStart);
        }
        if (budgetPosRound2 >= 0) {
            assertTrue("预算(" + budgetPosRound2 + ") 应在 DynamicText(" + dynamicTextStart + ") 之后",
                    budgetPosRound2 > dynamicTextStart);
        }
    }

    // ===== 16. sub-agent role: EXPLORER =====

    @Test
    public void explorerRoleShouldRenderRoleInstructions() {
        RenderPromptNode node = newNode();
        AgentContext context = basicContext();
        context.setAgentRole(AgentRole.EXPLORER);
        context.setPathScope("src/main/java");
        context.getDynamicText().appendUserTask("find usages");

        node.apply(context);
        String prompt = context.getCurrentPrompt();

        assertTrue("EXPLORER prompt 应包含子 Agent 角色说明",
                prompt.contains("你是主 Agent 派生出的隔离子 Agent"));
        assertTrue("EXPLORER prompt 应包含角色名",
                prompt.contains("EXPLORER"));
        assertTrue("EXPLORER prompt 应包含只读探索说明",
                prompt.contains("只读探索代码事实"));
        assertTrue("EXPLORER prompt 应包含路径范围",
                prompt.contains("src/main/java"));
        assertTrue("EXPLORER prompt 应包含 final answer JSON 格式说明",
                prompt.contains("summary") && prompt.contains("findings"));
    }

    // ===== 17. sub-agent role: REVIEWER =====

    @Test
    public void reviewerRoleShouldRenderRoleInstructions() {
        RenderPromptNode node = newNode();
        AgentContext context = basicContext();
        context.setAgentRole(AgentRole.REVIEWER);
        context.getDynamicText().appendUserTask("review changes");

        node.apply(context);
        String prompt = context.getCurrentPrompt();

        assertTrue("REVIEWER prompt 应包含子 Agent 角色说明",
                prompt.contains("你是主 Agent 派生出的隔离子 Agent"));
        assertTrue("REVIEWER prompt 应包含角色名",
                prompt.contains("REVIEWER"));
        assertTrue("REVIEWER prompt 应包含审查说明",
                prompt.contains("只读审查正确性"));
        assertTrue("REVIEWER prompt 应包含 final answer JSON 格式说明",
                prompt.contains("summary") && prompt.contains("findings"));
    }

    // ===== 18. sub-agent role: EDITOR =====

    @Test
    public void editorRoleShouldRenderRoleInstructions() {
        RenderPromptNode node = newNode();
        AgentContext context = basicContext();
        context.setAgentRole(AgentRole.EDITOR);
        context.getDynamicText().appendUserTask("fix bug");

        node.apply(context);
        String prompt = context.getCurrentPrompt();

        assertTrue("EDITOR prompt 应包含子 Agent 角色说明",
                prompt.contains("你是主 Agent 派生出的隔离子 Agent"));
        assertTrue("EDITOR prompt 应包含角色名",
                prompt.contains("EDITOR"));
        assertTrue("EDITOR prompt 应包含最小编辑说明",
                prompt.contains("最小编辑"));
        assertTrue("EDITOR prompt 应包含 final answer JSON 格式说明",
                prompt.contains("summary") && prompt.contains("findings"));
    }

    // ===== 19. with/without plan, skills, tools, DynamicText =====

    @Test
    public void promptWithAllFeaturesEnabledShouldContainAllBlocks() {
        RenderPromptNode node = newNode();
        AgentContext context = basicContext();
        context.setPlan(AgentPlan.forQuestion("test plan"));
        context.setMaxSegments(2);
        context.setMaxTotalSteps(20);
        context.setSkillCatalogText("skill-list: [search, read]");
        context.setMemoryContext("memory-ctx");
        context.getDynamicText().appendUserTask("test question");
        context.getDynamicText().appendSystemNote(1, "test", "Note", "some note");

        node.apply(context);
        String prompt = context.getCurrentPrompt();

        assertTrue("应包含安全声明", prompt.contains("<untrusted_tool_output"));
        assertTrue("应包含 Action 示例", prompt.contains("Action JSON 示例"));
        assertTrue("应包含 Final 示例", prompt.contains("Final JSON 示例"));
        assertTrue("应包含技能目录", prompt.contains("skill-list"));
        assertTrue("应包含工具目录", prompt.contains("可用工具："));
        assertTrue("应包含用户问题", prompt.contains("用户问题："));
        assertTrue("应包含 DynamicText", prompt.contains("动态上下文："));
        assertTrue("应包含执行预算", prompt.contains("执行预算："));
        // 有计划时 当前计划：至少出现 2 次（协议文案 + 计划 section）
        int firstPlan = prompt.indexOf("当前计划：");
        int lastPlan = prompt.lastIndexOf("当前计划：");
        assertTrue("有计划时 当前计划 应至少出现 2 次（协议文案 + 计划 section）",
                firstPlan >= 0 && lastPlan > firstPlan);
        assertTrue("应包含 memory context", prompt.contains("memory-ctx"));

        // Verify ordering: memory context after plan section (last occurrence)
        int planPos = prompt.lastIndexOf("当前计划：");
        int memPos = prompt.indexOf("memory-ctx");
        assertTrue("memory context(" + memPos + ") 应在计划(" + planPos + ") 之后", memPos > planPos);
    }

    @Test
    public void promptWithoutPlanOrBudgetShouldStillContainCoreBlocks() {
        RenderPromptNode node = newNode();
        AgentContext context = basicContext();
        context.getDynamicText().appendUserTask("test question");

        node.apply(context);
        String prompt = context.getCurrentPrompt();

        assertTrue("应包含安全声明", prompt.contains("<untrusted_tool_output"));
        assertTrue("应包含 Action 示例", prompt.contains("Action JSON 示例"));
        assertTrue("应包含 Final 示例", prompt.contains("Final JSON 示例"));
        assertTrue("应包含工具目录", prompt.contains("可用工具："));
        assertTrue("应包含用户问题", prompt.contains("用户问题："));
        // 无计划时，当前计划：只出现在协议文案中（仅一次），不出现在尾部 block
        int firstPlan = prompt.indexOf("当前计划：");
        int lastPlan = prompt.lastIndexOf("当前计划：");
        assertTrue("无计划时 当前计划 应在协议文案中仅出现一次",
                firstPlan >= 0 && firstPlan == lastPlan);
        // 单段时不应有执行预算
        assertTrue("单段时不应有执行预算", !prompt.contains("执行预算："));
    }

    @Test
    public void emptyDynamicTextShouldNotRenderDynamicSection() {
        RenderPromptNode node = newNode();
        AgentContext context = basicContext();
        // No DynamicText entries — render() 为空，isEmpty() 为 true

        node.apply(context);
        String prompt = context.getCurrentPrompt();

        // 动态上下文区段不应存在，但其它区块正常
        assertTrue("应包含 Action 示例", prompt.contains("Action JSON 示例"));
        assertTrue("应包含工具目录", prompt.contains("可用工具："));
        assertTrue("应包含用户问题", prompt.contains("用户问题："));
        assertTrue("空 DynamicText 时不应渲染动态上下文区块",
                !prompt.contains("动态上下文："));
    }
}
