package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentDispatchResult;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.SubAgentStatus;
import cn.lunalhx.ai.domain.agent.service.subagent.SubAgentCoordinator;
import cn.lunalhx.ai.domain.agent.service.subagent.SubAgentToolSpecs;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class SubAgentDispatchNode extends AbstractAgentNode {

    private final SubAgentCoordinator coordinator;
    private final AgentRuntimeProperties properties;

    public SubAgentDispatchNode(SubAgentCoordinator coordinator, AgentRuntimeProperties properties) {
        super(AgentNodeNames.SUB_AGENT_DISPATCH, List.of("decision.input", "agentDepth", "subAgentPolicy"));
        this.coordinator = coordinator;
        this.properties = properties;
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        long startedAt = System.currentTimeMillis();
        AgentDecision decision = context.getDecision();
        context.setStep(context.getStep() + 1);
        context.getDynamicText().appendAssistantAction(context.getStep(), name(), decision);

        List<AgentEvent> events = new ArrayList<>();
        events.add(event(context, AgentEventType.THOUGHT)
                .step(context.getStep())
                .thought(decision == null ? null : decision.getThought())
                .build());
        events.add(event(context, AgentEventType.TOOL_CALL)
                .step(context.getStep())
                .thought(decision == null ? null : decision.getThought())
                .tool(SubAgentToolSpecs.SPAWN_AGENTS)
                .input(decision == null ? null : decision.getInputView())
                .workspace(context.getWorkspaceDisplayName())
                .build());

        SubAgentDispatchResult result = coordinator.dispatch(context);
        ToolResult toolResult = result.isSuccess()
                ? ToolResult.success(result.getObservation(), result.isTruncated(), result.getElapsedMs())
                : ToolResult.failure(result.getErrorCode(), result.getMessage(), result.getElapsedMs());
        if (StringUtils.length(toolResult.getObservation()) > properties.getObservationMaxChars()) {
            toolResult.setObservation(StringUtils.abbreviate(toolResult.getObservation(), properties.getObservationMaxChars()));
            toolResult.setTruncated(true);
        }
        context.setToolResult(toolResult);
        events.addAll(toSubAgentEvents(context, result));
        events.add(event(context, AgentEventType.SUB_AGENT_SUMMARY)
                .step(context.getStep())
                .tool(SubAgentToolSpecs.SPAWN_AGENTS)
                .observation(toolResult.getObservation())
                .truncated(toolResult.isTruncated())
                .elapsedMs(System.currentTimeMillis() - startedAt)
                .build());
        return NodeResult.next(AgentNodeNames.OBSERVATION, events);
    }

    private List<AgentEvent> toSubAgentEvents(AgentContext context, SubAgentDispatchResult result) {
        List<AgentEvent> events = new ArrayList<>();
        for (SubAgentResult child : result.getResults()) {
            events.add(event(context, AgentEventType.SUB_AGENT_STARTED)
                    .step(context.getStep())
                    .subAgentTaskId(child.getTaskId())
                    .subAgentRunId(child.getRunId())
                    .subAgentRole(child.getRole() == null ? null : child.getRole().name())
                    .subAgentStatus("STARTED")
                    .build());
            AgentEventType type = (child.getStatus() == SubAgentStatus.SUCCEEDED
                    || child.getStatus() == SubAgentStatus.PARTIAL)
                    ? AgentEventType.SUB_AGENT_COMPLETED
                    : AgentEventType.SUB_AGENT_FAILED;
            events.add(event(context, type)
                    .step(context.getStep())
                    .subAgentTaskId(child.getTaskId())
                    .subAgentRunId(child.getRunId())
                    .subAgentRole(child.getRole() == null ? null : child.getRole().name())
                    .subAgentStatus(child.getStatus() == null ? null : child.getStatus().name())
                    .message(child.getMessage())
                    .elapsedMs(child.getElapsedMs())
                    .build());
        }
        return events;
    }

}
