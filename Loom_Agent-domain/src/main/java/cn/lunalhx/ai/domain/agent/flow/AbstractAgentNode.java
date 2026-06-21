package cn.lunalhx.ai.domain.agent.flow;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentStep;
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
        return AgentEvent.builder()
                .type(type)
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId());
    }

    protected void fail(AgentContext context, AgentStopReason reason, String code, String message) {
        context.setStopReason(reason);
        context.setErrorCode(code);
        context.setErrorMessage(message);
    }

    protected void appendStep(AgentContext context, boolean success) {
        AgentDecision decision = context.getDecision();
        ToolResult result = context.getToolResult();
        context.getHistory().add(AgentStep.builder()
                .step(Math.max(1, context.getStep()))
                .thought(decision == null ? null : decision.getThought())
                .tool(decision == null ? "model_parse" : decision.getTool())
                .input(decision == null ? context.getModelOutput() : String.valueOf(decision.getInputView()))
                .observation(result == null ? null : result.getObservation())
                .success(success)
                .build());
    }

    protected List<AgentEvent> observationEvents(AgentContext context) {
        ToolResult result = context.getToolResult();
        return List.of(event(context, AgentEventType.OBSERVATION)
                .step(context.getStep())
                .tool(context.getDecision() == null ? null : context.getDecision().getTool())
                .observation(result == null ? null : result.getObservation())
                .truncated(result != null && result.isTruncated())
                .build());
    }

}
