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
import cn.lunalhx.ai.domain.agent.service.context.ContextWindowManager;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class ToolDispatchNode extends AbstractAgentNode {

    private final ToolRegistry toolRegistry;
    private final AgentRuntimeProperties properties;
    private final AgentHookRegistry hookRegistry;
    private final ContextWindowManager contextWindowManager;

    public ToolDispatchNode(ToolRegistry toolRegistry,
                            AgentRuntimeProperties properties,
                            AgentHookRegistry hookRegistry,
                            ContextWindowManager contextWindowManager) {
        super(AgentNodeNames.TOOL_DISPATCH, List.of("decision.tool", "decision.input", "step"));
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "hookRegistry must not be null");
        this.contextWindowManager = Objects.requireNonNull(contextWindowManager, "contextWindowManager must not be null");
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        AgentDecision decision = context.getDecision();
        ToolCall toolCall = ToolCall.builder()
                .name(decision.getTool())
                .input(decision.getInput())
                .workspace(context.getWorkspace())
                .workspaceRoot(context.getResolvedWorkspace())
                .runId(context.getRunId())
                .rootRunId(context.getRootRunId())
                .conversationId(context.getConversationId())
                .approvedPolicyFingerprint(context.getApprovedPolicyFingerprint())
                .activeSkillNames(context.getActivatedSkills() == null ? List.of()
                        : context.getActivatedSkills().stream()
                                .map(cn.lunalhx.ai.domain.agent.model.entity.SkillActivation::name)
                                .collect(Collectors.toList()))
                .build();
        ToolPolicyDecision policy = toolRegistry.policy(toolCall);
        boolean resumedApproval = StringUtils.equals(context.getApprovedTool(), decision.getTool());
        String currentPolicyFingerprint = policy == null ? null : policy.getPolicyFingerprint();
        boolean fingerprintedApproval = resumedApproval
                && (StringUtils.isNotBlank(context.getApprovedPolicyFingerprint())
                || StringUtils.isNotBlank(currentPolicyFingerprint));
        if (fingerprintedApproval
                && !StringUtils.equals(context.getApprovedPolicyFingerprint(), currentPolicyFingerprint)) {
            clearApprovedPolicy(context);
            return NodeResult.next(AgentNodeNames.APPROVAL_GATE, List.of());
        }
        clearApprovedPolicy(context);

        context.setStep(context.getStep() + 1);
        context.setUnsafeResumeRequired(policy != null && (policy.getPermissionLevel() == ToolPermissionLevel.WRITE_CONFIRM
                || policy.getPermissionLevel() == ToolPermissionLevel.HIGH_RISK_CONFIRM));

        List<AgentEvent> events = new ArrayList<>();
        events.addAll(hookRegistry.trigger(AgentHookEvent.BEFORE_TOOL, AgentHookContext.builder()
                .agentContext(context)
                .node(name())
                .toolCall(toolCall)
                .reason("before_tool:" + decision.getTool())
                .build()));

        ToolResult result = toolRegistry.call(toolCall);
        if (resumedApproval && "approval_stale".equals(result.getErrorCode())) {
            context.setUnsafeResumeRequired(false);
            return NodeResult.next(AgentNodeNames.APPROVAL_GATE, events);
        }
        context.setUnsafeResumeRequired(false);
        result = contextWindowManager.prepareToolResult(context, result);
        if (!contextEnabled() && StringUtils.length(result.getObservation()) > properties.getObservationMaxChars()) {
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

    private void clearApprovedPolicy(AgentContext context) {
        context.setApprovedTool(null);
        context.setApprovedPolicyFingerprint(null);
    }

    private ToolResult applyTodoWrite(AgentContext context, ToolResult original) {
        try {
            if (context.getPlan() == null) {
                context.setPlan(new cn.lunalhx.ai.domain.agent.model.entity.AgentPlan());
            }
            int submittedTodos = context.getDecision().getInput().path("todos").size();
            context.getPlan().applyTodoWrite(context.getDecision().getInput());
            return ToolResult.success("Updated " + submittedTodos + " planned tasks", false, original.getElapsedMs());
        } catch (Exception e) {
            return ToolResult.failure("todo_write_failed", e.getMessage(), original.getElapsedMs());
        }
    }

    private boolean contextEnabled() {
        return properties.getContext() != null && Boolean.TRUE.equals(properties.getContext().getEnabled());
    }

}
