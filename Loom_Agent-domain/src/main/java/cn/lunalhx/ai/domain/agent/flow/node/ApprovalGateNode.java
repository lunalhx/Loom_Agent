package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ApprovalDiff;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ApprovalGateNode extends AbstractAgentNode {

    private final ToolRegistry toolRegistry;
    private final ApprovalStore approvalStore;
    private final AgentRuntimeProperties properties;

    public ApprovalGateNode(ToolRegistry toolRegistry, ApprovalStore approvalStore, AgentRuntimeProperties properties) {
        super(AgentNodeNames.APPROVAL_GATE, List.of("decision.tool", "decision.input", "toolPolicy"));
        this.toolRegistry = toolRegistry;
        this.approvalStore = approvalStore;
        this.properties = properties;
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        ToolPolicyDecision policy = toolRegistry.policy(ToolCall.builder()
                .name(context.getDecision().getTool())
                .input(context.getDecision().getInput())
                .workspace(context.getWorkspace())
                .workspaceRoot(context.getResolvedWorkspace())
                .runId(context.getRunId())
                .rootRunId(context.getRootRunId())
                .conversationId(context.getConversationId())
                .build());
        if (policy == null || policy.getPermissionLevel() == null || policy.getPermissionLevel() == ToolPermissionLevel.READ_ONLY) {
            return NodeResult.next(AgentNodeNames.TOOL_DISPATCH, List.of());
        }
        if (policy.getPermissionLevel() == ToolPermissionLevel.HIGH_RISK_DENY) {
            return deny(context, policy);
        }
        if (policy.getPermissionLevel() == ToolPermissionLevel.HIGH_RISK_CONFIRM) {
            String highRiskPolicy = StringUtils.defaultString(properties.getHighRiskPolicy(), "CONFIRM").toUpperCase();
            return switch (highRiskPolicy) {
                case "DENY" -> deny(context, policy);
                case "ALLOW" -> NodeResult.next(AgentNodeNames.TOOL_DISPATCH, List.of());
                default -> requireApproval(context, policy);
            };
        }
        return requireApproval(context, policy);
    }

    private NodeResult requireApproval(AgentContext context, ToolPolicyDecision policy) {
        Instant now = Instant.now();
        String approvalId = UUID.randomUUID().toString();
        context.setPendingApprovalId(approvalId);
        Map<String, Object> inputSummary = summarizeInput(context.getDecision().getInputView());
        ApprovalDiff diff = policy.getDiff();
        PendingApproval approval = PendingApproval.builder()
                .approvalId(approvalId)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .resolvedWorkspace(context.getResolvedWorkspace())
                .workspace(context.getWorkspace())
                .workspaceDisplayName(context.getWorkspaceDisplayName())
                .tool(context.getDecision().getTool())
                .input(inputSummary)
                .permissionLevel(policy.getPermissionLevel())
                .riskReason(policy.getRiskReason())
                .operationPreview(policy.getOperationPreview())
                .diff(diff)
                .createdAt(now)
                .expiresAt(now.plusSeconds(Math.max(1L, properties.getApprovalTtlSeconds())))
                .context(context)
                .build();
        approvalStore.save(approval);
        boolean highRisk = policy.getPermissionLevel() == ToolPermissionLevel.HIGH_RISK_CONFIRM;
        AgentEventType eventType = highRisk ? AgentEventType.HIGH_RISK_APPROVAL_REQUIRED : AgentEventType.APPROVAL_REQUIRED;
        return NodeResult.terminal(List.of(event(context, eventType)
                .step(context.getStep() + 1)
                .tool(context.getDecision().getTool())
                .input(inputSummary)
                .approvalId(approvalId)
                .workspace(context.getWorkspaceDisplayName())
                .permissionLevel(policy.getPermissionLevel().name())
                .riskReason(policy.getRiskReason())
                .operationPreview(policy.getOperationPreview())
                .diff(diff)
                .expiresAt(approval.getExpiresAt())
                .build()));
    }

    private Map<String, Object> summarizeInput(Map<String, Object> input) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (input == null) {
            return summary;
        }
        input.forEach((key, value) -> summary.put(key, summarizeValue(key, value)));
        return summary;
    }

    private Object summarizeValue(String key, Object value) {
        if (value instanceof String text) {
            if ("content".equals(key) || "oldText".equals(key) || "newText".equals(key)) {
                return "<" + text.length() + " chars>";
            }
            return StringUtils.abbreviate(text, 200);
        }
        return value;
    }

    private NodeResult deny(AgentContext context, ToolPolicyDecision policy) {
        context.setStep(context.getStep() + 1);
        String reason = StringUtils.defaultIfBlank(policy.getRiskReason(), "高危动作已被策略拦截");
        ToolResult result = ToolResult.failure("policy_denied", reason, 0L);
        context.setToolResult(result);
        context.getDynamicText().appendAssistantAction(context.getStep(), name(), context.getDecision());
        context.getDynamicText().appendToolResult(
                context.getStep(),
                name(),
                context.getDecision(),
                "Success: false\nObservation:\n" + result.getObservation());
        appendStep(context, false);

        List<AgentEvent> events = new ArrayList<>();
        events.add(event(context, AgentEventType.POLICY_DENIED)
                .step(context.getStep())
                .tool(context.getDecision().getTool())
                .input(context.getDecision().getInputView())
                .workspace(context.getWorkspaceDisplayName())
                .permissionLevel(policy.getPermissionLevel().name())
                .riskReason(reason)
                .operationPreview(policy.getOperationPreview())
                .observation(result.getObservation())
                .build());
        events.addAll(observationEvents(context));
        return NodeResult.next(AgentNodeNames.REPLAN_GUARD, events);
    }

}
