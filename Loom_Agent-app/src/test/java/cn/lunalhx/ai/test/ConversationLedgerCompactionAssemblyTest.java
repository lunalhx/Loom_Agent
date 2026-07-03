package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.flow.node.RenderPromptNode;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedger;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedgerEntry;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.LedgerStableType;
import cn.lunalhx.ai.domain.agent.service.context.ContextWindowManager;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerInitializer;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerBootstrapService;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerCompactionService;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerWatermark;
import cn.lunalhx.ai.domain.agent.service.prompt.LedgerPromptServices;
import cn.lunalhx.ai.domain.agent.service.prompt.RenderPromptResources;
import cn.lunalhx.ai.domain.agent.service.prompt.StablePrefixBuilder;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryContextArtifactRepository;
import cn.lunalhx.ai.infrastructure.context.InMemoryContextBlobStore;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D1R-B: 生产装配级压缩测试。
 *
 * <p>通过真实 Factory 链路创建 RenderPromptNode（含 LedgerCompactionService），
 * 验证 high/low watermark、generation、artifact 和二次不重复压缩。
 */
public class ConversationLedgerCompactionAssemblyTest {

    private AgentRuntimeProperties properties;
    private AgentRuntimeProperties.ConversationLedgerProperties ledgerConfig;
    private ContextArtifactRepository artifactRepository;
    private ContextBlobStore blobStore;
    private ContextWindowManager cwm;
    private ConversationLedgerAppendService appendService;
    private LedgerBootstrapService bootstrapService;
    private LedgerCompactionService compactionService;

    @Before
    public void setUp() {
        properties = AgentRuntimeTestFixture.standardProperties();
        properties.getContext().setEnabled(false);

        ledgerConfig = properties.getConversationLedger();
        ledgerConfig.setEnabled(true);
        ledgerConfig.setShadowEnabled(false);
        // 低水位 2, 高水位 3 → 超过 3 条触发压缩到 ≤2
        ledgerConfig.setCompactionHighWatermark(3);
        ledgerConfig.setCompactionLowWatermark(2);

        artifactRepository = new InMemoryContextArtifactRepository();
        blobStore = new InMemoryContextBlobStore();
        cwm = new ContextWindowManager(properties, artifactRepository, blobStore);

        appendService = new ConversationLedgerAppendService(ledgerConfig);
        ConversationLedgerInitializer initializer = new ConversationLedgerInitializer(ledgerConfig);
        bootstrapService = new LedgerBootstrapService(appendService, initializer);

        LedgerWatermark watermark = LedgerWatermark.fromConfig(
                ledgerConfig.getCompactionHighWatermark(),
                ledgerConfig.getCompactionLowWatermark());
        compactionService = new LedgerCompactionService(watermark, ledgerConfig,
                artifactRepository, blobStore);
    }

    // ================================================================
    // 装配测试
    // ================================================================

    @Test
    public void compactionTriggersWhenOverHighWatermark() {
        RenderPromptNode node = createNode();
        AgentContext ctx = ledgedContext();
        stableBootstrap(ctx);
        addEntries(ctx, 5); // 超过 high=3

        node.apply(ctx);

        ConversationLedger ledger = ctx.getConversationLedger();
        assertThat(ledger.size()).isLessThanOrEqualTo(3); // ≤2 + summary
        assertThat(ctx.getGeneration()).isGreaterThanOrEqualTo(1); // bumped from 0
        assertThat(ctx.getLedgerBaselineArtifactId()).isNotNull();
    }

    @Test
    public void noCompactionWhenBelowHighWatermark() {
        RenderPromptNode node = createNode();
        AgentContext ctx = ledgedContext();
        stableBootstrap(ctx);
        addEntries(ctx, 2); // ≤ high=3

        int genBefore = ctx.getGeneration();
        node.apply(ctx);

        // 不应触发压缩
        assertThat(ctx.getGeneration()).isEqualTo(genBefore);
        assertThat(ctx.getLedgerBaselineArtifactId()).isNull();
    }

    @Test
    public void compactionIsIdempotentWithinSameGeneration() {
        RenderPromptNode node = createNode();
        AgentContext ctx = ledgedContext();
        stableBootstrap(ctx);
        addEntries(ctx, 5);

        // 第一次压缩
        node.apply(ctx);
        int gen1 = ctx.getGeneration();
        String artifact1 = ctx.getLedgerBaselineArtifactId();
        int size1 = ctx.getConversationLedger().size();

        // 立即再调用 — 不应再次压缩
        node.apply(ctx);
        int gen2 = ctx.getGeneration();
        int size2 = ctx.getConversationLedger().size();

        assertThat(gen2).isEqualTo(gen1);           // generation 不变
        assertThat(ctx.getLedgerBaselineArtifactId()).isEqualTo(artifact1);
        assertThat(size2).isEqualTo(size1);          // 条目数不变
    }

    @Test
    public void compactionAgainAfterMoreEntries() {
        RenderPromptNode node = createNode();
        AgentContext ctx = ledgedContext();
        stableBootstrap(ctx);
        addEntries(ctx, 5);

        // 第一次压缩
        node.apply(ctx);
        int gen1 = ctx.getGeneration();

        // 追加更多条目，再次超过高水位
        addEntries(ctx, 3);
        node.apply(ctx);

        assertThat(ctx.getGeneration()).isGreaterThan(gen1); // 再次 bump
    }

    @Test
    public void disabledLedgerNoCompaction() {
        // 使用 disabled 配置
        ledgerConfig.setEnabled(false);
        appendService = new ConversationLedgerAppendService(ledgerConfig);
        ConversationLedgerInitializer initializer = new ConversationLedgerInitializer(ledgerConfig);
        bootstrapService = new LedgerBootstrapService(appendService, initializer);
        LedgerWatermark watermark = LedgerWatermark.fromConfig(3, 2);
        compactionService = new LedgerCompactionService(watermark, ledgerConfig,
                artifactRepository, blobStore);

        RenderPromptNode node = createNode();
        AgentContext ctx = ledgedContext();
        stableBootstrap(ctx);
        addEntries(ctx, 5);

        int genBefore = ctx.getGeneration();
        node.apply(ctx);

        // ledger disabled → 不压缩
        assertThat(ctx.getGeneration()).isEqualTo(genBefore);
        assertThat(ctx.getLedgerBaselineArtifactId()).isNull();
    }

    @Test
    public void compactionServiceNotNullInProductionNode() {
        RenderPromptNode node = createNode();
        AgentContext ctx = ledgedContext();
        stableBootstrap(ctx);
        addEntries(ctx, 5);

        // 节点应有 compaction service
        node.apply(ctx);
        assertThat(ctx.getLedgerBaselineArtifactId()).isNotNull();
    }

    @Test
    public void transcriptArtifactIsPersisted() {
        RenderPromptNode node = createNode();
        AgentContext ctx = ledgedContext();
        stableBootstrap(ctx);
        addEntries(ctx, 5);

        node.apply(ctx);

        String artifactId = ctx.getLedgerBaselineArtifactId();
        assertThat(artifactId).isNotNull();

        var artifact = artifactRepository.findByArtifactIdAndRootRunId(
                artifactId, ctx.getRootRunId());
        assertThat(artifact).isPresent();
        assertThat(blobStore.read(artifact.get().getStorageUri())).isNotBlank();
    }

    // ================================================================
    // Helpers
    // ================================================================

    private RenderPromptNode createNode() {
        RenderPromptResources resources = RenderPromptResources.withStorage(
                artifactRepository, blobStore);
        LedgerPromptServices ledgerServices = new LedgerPromptServices(
                null, bootstrapService, new StablePrefixBuilder(), compactionService);
        return new RenderPromptNode(cwm, resources, ledgerServices);
    }

    private AgentContext ledgedContext() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-1");
        ctx.setRootRunId("run-1");
        ctx.setRequestId("req-1");
        ctx.setConversationId("conv-1");
        ctx.setQuestion("test compaction");
        ctx.setToolSpecs(List.of(
                new ToolSpec("read", "Read a file", "{\"path\":\"string\"}")));
        ctx.setMaxSteps(10);
        ctx.setStep(0);
        return ctx;
    }

    private void stableBootstrap(AgentContext ctx) {
        ConversationLedgerInitializer initializer = new ConversationLedgerInitializer(ledgerConfig);
        StablePrefixBuilder prefixBuilder = new StablePrefixBuilder();
        var stablePrefix = prefixBuilder.build(null, false, null,
                ctx.getToolSpecs(), null, null, null);
        initializer.initializeNewConversation(ctx, stablePrefix);
        ctx.setStablePrefix(stablePrefix);
        ctx.setLedgerReady(true);
    }

    private void addEntries(AgentContext ctx, int count) {
        for (int i = 1; i <= count; i++) {
            String eventKey = "evt-" + i;
            appendService.appendAssistant(ctx, "assistant action " + i, eventKey);
        }
    }
}
