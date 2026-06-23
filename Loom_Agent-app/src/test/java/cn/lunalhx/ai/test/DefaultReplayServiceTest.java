package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.node.StartNode;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentReplayTimeline;
import cn.lunalhx.ai.domain.agent.service.DefaultReplayService;
import cn.lunalhx.ai.domain.agent.service.InMemoryTraceRecorder;
import org.junit.Test;

import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DefaultReplayServiceTest {

    @Test
    public void shouldReplaySingleRunWithoutGeneratingCostOrMutatingTrace() {
        InMemoryTraceRecorder traceRecorder = new InMemoryTraceRecorder();
        AgentContext root = context("root-run", null, "root-run", "trace-root");
        traceRecorder.recordNodeStart(root, new StartNode(), null);
        traceRecorder.recordStop(root, "completed", "done");
        int eventCount = traceRecorder.timeline("root-run").size();

        AgentReplayTimeline timeline = new DefaultReplayService(traceRecorder)
                .replayRun("root-run", false);

        assertEquals("DRY_REPLAY", timeline.getMode());
        assertEquals("trace-root", timeline.getTraceId());
        assertEquals("root-run", timeline.getRootRunId());
        assertEquals("root-run", timeline.getRunId());
        assertFalse(timeline.getIncludeChildren());
        assertFalse(timeline.getCostGenerated());
        assertEquals(eventCount, timeline.getEvents().size());
        assertEquals(eventCount, traceRecorder.timeline("root-run").size());
    }

    @Test
    public void shouldIncludeChildRunsFromSameTraceWhenRequested() {
        InMemoryTraceRecorder traceRecorder = new InMemoryTraceRecorder();
        AgentContext root = context("root-run", null, "root-run", "trace-root");
        AgentContext child = context("child-run", "root-run", "root-run", "trace-root");
        traceRecorder.recordNodeStart(root, new StartNode(), null);
        traceRecorder.recordNodeStart(child, new StartNode(), null);

        AgentReplayTimeline withChildren = new DefaultReplayService(traceRecorder)
                .replayRun("root-run", true);
        AgentReplayTimeline rootOnly = new DefaultReplayService(traceRecorder)
                .replayRun("root-run", false);

        assertEquals(2, withChildren.getEvents().size());
        assertEquals("child-run", withChildren.getEvents().get(1).getRunId());
        assertEquals("root-run,child-run", withChildren.getEvents().stream()
                .map(event -> event.getRunId())
                .collect(Collectors.joining(",")));
        assertEquals(1, rootOnly.getEvents().size());
        assertEquals("root-run", rootOnly.getEvents().get(0).getRunId());
    }

    @Test
    public void shouldSummarizeLargePromptAndModelOutputInCheckpoint() {
        AgentContext context = context("checkpoint-run", null, "checkpoint-run", "trace-checkpoint");
        context.setCurrentNode(AgentNodeNames.MODEL_CALL);
        context.setCurrentPrompt("p".repeat(6000));
        context.setModelOutput("o".repeat(6000));

        AgentContextSnapshot snapshot = AgentContextSnapshot.from(context);

        assertTrue(snapshot.getCurrentPrompt().contains("checkpoint_truncated"));
        assertTrue(snapshot.getCurrentPrompt().contains("sha256="));
        assertTrue(snapshot.getCurrentPrompt().length() < 4200);
        assertTrue(snapshot.getModelOutput().contains("checkpoint_truncated"));
    }

    private AgentContext context(String runId, String parentRunId, String rootRunId, String traceId) {
        AgentContext context = new AgentContext();
        context.setRunId(runId);
        context.setParentRunId(parentRunId);
        context.setRootRunId(rootRunId);
        context.setTraceId(traceId);
        context.setRequestId("request-" + runId);
        context.setConversationId("conversation-" + runId);
        context.setQuestion("test");
        context.setMaxSteps(3);
        return context;
    }

}
