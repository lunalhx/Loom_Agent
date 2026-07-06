package cn.lunalhx.ai.domain.agent.flow;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentStep;
import cn.lunalhx.ai.domain.agent.model.state.AgentActionState;
import cn.lunalhx.ai.domain.agent.model.state.AgentIdentity;
import cn.lunalhx.ai.domain.agent.model.state.AgentRuntimeState;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.tool.model.ToolResult;

import java.util.List;

public abstract class AbstractAgentNode implements AgentNode {

    private final String name;
    private final List<String> inputKeys;

    protected AbstractAgentNode(String name, List<String> inputKeys) {
        this.name = name;
        this.inputKeys = inputKeys;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<String> inputKeys() {
        return inputKeys;
    }

    @Override
    public final NodeResult apply(AgentContext context) {
        return doApply(context);
    }

    protected abstract NodeResult doApply(AgentContext context);

    protected AgentEvent.AgentEventBuilder event(AgentContext context, AgentEventType type) {
        AgentIdentity id = context.identity();
        return AgentEvent.builder()
                .type(type)
                .runId(id.runId())
                .requestId(id.requestId())
                .conversationId(id.conversationId())
                .workspace(context.environment().workspaceDisplayName())
                .parentRunId(id.parentRunId());
    }

    protected void fail(AgentContext context, AgentStopReason reason, String code, String message) {
        context.runtime().fail(reason, code, message);
    }

    protected void appendStep(AgentContext context, boolean success) {
        AgentRuntimeState runtime = context.runtime();
        AgentActionState action = context.action();
        AgentDecision decision = action.decision();
        ToolResult result = action.toolResult();
        runtime.history().add(AgentStep.builder()
                .step(Math.max(1, runtime.step()))
                .thought(decision == null ? null : decision.getReason())
                .tool(decision == null ? "model_parse" : decision.getTool())
                .input(decision == null ? context.prompt().modelOutput() : String.valueOf(decision.getInputView()))
                .observation(result == null ? null : result.getObservation())
                .success(success)
                .build());
    }

    protected List<AgentEvent> observationEvents(AgentContext context) {
        AgentRuntimeState runtime = context.runtime();
        AgentActionState action = context.action();
        ToolResult result = action.toolResult();
        return List.of(event(context, AgentEventType.OBSERVATION)
                .step(runtime.step())
                .tool(action.decision() == null ? null : action.decision().getTool())
                .observation(result == null ? null : result.getObservation())
                .truncated(result != null && result.isTruncated())
                .metadata(result == null ? null : result.getDetails())
                .build());
    }
}
