package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookContext;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookEvent;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookRegistry;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class ToolDispatchNode extends AbstractAgentNode {

    private final ToolRegistry toolRegistry;
    private final AgentRuntimeProperties properties;
    private final AgentHookRegistry hookRegistry;

    public ToolDispatchNode(ToolRegistry toolRegistry, AgentRuntimeProperties properties) {
        this(toolRegistry, properties, AgentHookRegistry.empty());
    }

    public ToolDispatchNode(ToolRegistry toolRegistry, AgentRuntimeProperties properties, AgentHookRegistry hookRegistry) {
        super(AgentNodeNames.TOOL_DISPATCH, List.of("decision.tool", "decision.input", "step"));
        this.toolRegistry = toolRegistry;
        this.properties = properties;
        this.hookRegistry = hookRegistry;
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        AgentDecision decision = context.getDecision();
        context.setStep(context.getStep() + 1);
        ToolCall toolCall = ToolCall.builder()
                .name(decision.getTool())
                .input(decision.getInput())
                .workspaceRoot(context.getResolvedWorkspace())
                .build();
        ToolPolicyDecision policy = toolRegistry.policy(toolCall);
        context.setUnsafeResumeRequired(policy != null && policy.getPermissionLevel() == ToolPermissionLevel.WRITE_CONFIRM);

        List<AgentEvent> events = new ArrayList<>();
        events.addAll(hookRegistry.trigger(AgentHookEvent.BEFORE_TOOL, AgentHookContext.builder()
                .agentContext(context)
                .node(name())
                .toolCall(toolCall)
                .reason("before_tool:" + decision.getTool())
                .build()));

        ToolResult result = toolRegistry.call(toolCall);
        context.setUnsafeResumeRequired(false);
        if (StringUtils.length(result.getObservation()) > properties.getObservationMaxChars()) {
            result.setObservation(StringUtils.abbreviate(result.getObservation(), properties.getObservationMaxChars()));
            result.setTruncated(true);
        }
        if ("todo_write".equals(decision.getTool()) && result.isSuccess()) {
            result = applyTodoWrite(context, result);
        }
        context.setToolResult(result);
        context.getDynamicText().appendAssistantAction(context.getStep(), name(), decision);

        events.add(event(context, AgentEventType.THOUGHT)
                .step(context.getStep())
                .thought(decision.getThought())
                .build());
        events.add(event(context, AgentEventType.TOOL_CALL)
                .step(context.getStep())
                .thought(decision.getThought())
                .tool(decision.getTool())
                .input(decision.getInputView())
                .workspace(context.getWorkspaceDisplayName())
                .build());
        if ("todo_write".equals(decision.getTool()) && result.isSuccess() && context.getPlan() != null) {
            events.add(event(context, AgentEventType.PLAN_UPDATED)
                    .step(context.getStep())
                    .plan(context.getPlan().toView())
                    .build());
        }
        events.addAll(hookRegistry.trigger(AgentHookEvent.AFTER_TOOL, AgentHookContext.builder()
                .agentContext(context)
                .node(name())
                .toolCall(toolCall)
                .toolResult(result)
                .reason("after_tool:" + decision.getTool())
                .build()));
        return NodeResult.next(AgentNodeNames.OBSERVATION, events);
    }

    private ToolResult applyTodoWrite(AgentContext context, ToolResult original) {
        try {
            if (context.getPlan() == null) {
                context.setPlan(cn.lunalhx.ai.domain.agent.model.entity.AgentPlan.forQuestion(context.getQuestion()));
            }
            int submittedTodos = context.getDecision().getInput().path("todos").size();
            context.getPlan().applyTodoWrite(context.getDecision().getInput());
            return ToolResult.success("Updated " + submittedTodos + " planned tasks", false, original.getElapsedMs());
        } catch (Exception e) {
            return ToolResult.failure("todo_write_failed", e.getMessage(), original.getElapsedMs());
        }
    }

}
