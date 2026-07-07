package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.flow.node.RenderPromptNode;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentErrorCode;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
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

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RenderPromptNodeLedgerTest {

    private RenderPromptNode node;
    private ConversationLedgerAppendService appendService;

    @Before
    public void setUp() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getContext().setEnabled(false);
        InMemoryContextArtifactRepository artifacts = new InMemoryContextArtifactRepository();
        InMemoryContextBlobStore blobs = new InMemoryContextBlobStore();
        ConversationLedgerAppendService appendService = new ConversationLedgerAppendService();
        LedgerBootstrapService bootstrapService = new LedgerBootstrapService(
                appendService, new ConversationLedgerInitializer());
        this.appendService = appendService;
        LedgerCompactionService compactionService = new LedgerCompactionService(
                LedgerWatermark.defaults(), artifacts, blobs);
        node = new RenderPromptNode(
                new ContextWindowManager(properties, artifacts, blobs),
                RenderPromptResources.withStorage(artifacts, blobs),
                new LedgerPromptServices(
                        bootstrapService, new StablePrefixBuilder(), compactionService));
    }

    @Test
    public void successfulBootstrapRoutesToModelCall() {
        AgentContext context = context();

        NodeResult result = node.apply(context);

        assertEquals(AgentNodeNames.MODEL_CALL, result.getNextNode());
        assertTrue(context.isLedgerReady());
        assertNotNull(context.getStablePrefix());
        assertNotNull(context.getConversationLedger());
        assertEquals(1, context.getConversationLedger().size());
    }

    @Test
    public void bootstrapFailureRoutesToFailClosed() {
        AgentContext context = context();
        List<ToolSpec> invalidSpecs = new ArrayList<>();
        invalidSpecs.add(null);
        context.setToolSpecs(invalidSpecs);

        NodeResult result = node.apply(context);

        assertEquals(AgentNodeNames.FAIL, result.getNextNode());
        assertFalse(context.isLedgerReady());
        assertEquals(AgentErrorCode.LEDGER_BOOTSTRAP_FAILED.code(), context.getErrorCode());
    }

    @Test
    public void dynamicTextRemainsAvailableButIsNotRenderedAsPromptState() {
        AgentContext context = context();

        node.apply(context);

        String eventKey = ConversationLedgerInitializer.eventKey(context.getRunId(), "1", "test_note");
        appendService.appendSystemNote(context, "retained", eventKey);

        assertEquals(2, context.getConversationLedger().entries().size());
        assertTrue(context.getStablePrefix().frozenContent().contains("可用工具"));
        assertFalse(context.getStablePrefix().frozenContent().contains("retained"));
    }

    private AgentContext context() {
        AgentContext context = new AgentContext();
        context.setRunId("ledger-node");
        context.setRootRunId("ledger-node");
        context.setRequestId("request");
        context.setConversationId("conversation");
        context.setQuestion("test");
        context.setMaxSteps(10);
        context.setMaxTotalSteps(10);
        context.setMaxSegments(1);
        context.setToolSpecs(List.of(
                new ToolSpec("read_file", "Read a file", "{\"path\":\"string\"}")));
        return context;
    }
}
