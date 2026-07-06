package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookContext;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentRunRepository;
import cn.lunalhx.ai.runtime.hook.CheckpointAgentHook;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CheckpointAgentHookToolPhaseTest {

    @Test
    public void completedToolCheckpointShouldResumeAtObservation() throws Exception {
        InMemoryAgentCheckpointRepository checkpoints =
                new InMemoryAgentCheckpointRepository();
        CheckpointAgentHook hook = new CheckpointAgentHook(
                new InMemoryAgentRunRepository(), checkpoints, new ObjectMapper());
        AgentContext context = new AgentContext();
        context.setRunId("tool-phase-run");
        context.setRootRunId("tool-phase-run");
        context.setQuestion("test");
        ToolCall call = ToolCall.builder()
                .name("replace_in_file")
                .toolCallId("call-1")
                .input(JsonNodeFactory.instance.objectNode().put("path", "Demo.java"))
                .build();
        ToolResult result = ToolResult.success("updated", false, 1);
        context.setToolResult(result);

        hook.onEvent(AgentHookEvent.AFTER_TOOL, AgentHookContext.builder()
                .agentContext(context)
                .node(AgentNodeNames.TOOL_DISPATCH)
                .toolCall(call)
                .toolResult(result)
                .reason("after_tool:replace_in_file")
                .build());

        AgentCheckpoint checkpoint = checkpoints.latest("tool-phase-run")
                .orElseThrow();
        assertEquals(AgentNodeNames.OBSERVATION, checkpoint.getCurrentNode());
        assertTrue(checkpoint.getLastToolExecutionJson()
                .contains("\"phase\":\"COMPLETED\""));
        assertTrue(checkpoint.getLastToolExecutionJson()
                .contains("\"toolCallId\":\"call-1\""));
        assertTrue(checkpoint.getLastToolExecutionJson()
                .contains("\"inputFingerprint\":"));
    }
}
