package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentPlanItem;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ApprovalDiff;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import cn.lunalhx.ai.domain.tool.model.ToolOperation;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class ApprovalGateNode extends AbstractAgentNode {

    private final ToolRegistry toolRegistry;
    private final ApprovalStore approvalStore;
    private final AgentRuntimeProperties properties;

    public ApprovalGateNode(ToolRegistry toolRegistry, ApprovalStore approvalStore,
                            AgentRuntimeProperties properties) {
        super(AgentNodeNames.APPROVAL_GATE, List.of("decision.tool", "decision.input", "toolPolicy"));
        this.toolRegistry = toolRegistry;
        this.approvalStore = approvalStore;
        this.properties = properties;
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        ToolResult planGuardFailure = planGuardFailure(context);
        if (planGuardFailure != null) {
            return validationFailure(context, planGuardFailure);
        }
        ToolPolicyDecision policy = toolRegistry.policy(ToolCall.builder()
                .name(context.getDecision().getTool())
                .input(context.getDecision().getInput())
                .workspace(context.getWorkspace())
                .workspaceRoot(context.getResolvedWorkspace())
                .runId(context.getRunId())
                .rootRunId(context.getRootRunId())
                .conversationId(context.getConversationId())
                .build());
        if (policy != null && policy.hasValidationFailure()) {
            return validationFailure(context, policy);
        }
        if (policy == null || policy.getPermissionLevel() == null || policy.getPermissionLevel() == ToolPermissionLevel.READ_ONLY) {
            return NodeResult.next(AgentNodeNames.TOOL_DISPATCH, List.of());
        }
        if (policy.getPermissionLevel() == ToolPermissionLevel.HIGH_RISK_DENY) {
            return deny(context, policy);
        }
        // Check run-scoped approval grants for matching commands
        AgentRuntimeProperties.ShellCommandProperties sc = properties.getShellCommands();
        boolean grantsEnabled = sc == null || Boolean.TRUE.equals(sc.getSessionGrantsEnabled());
        if (grantsEnabled && "run_shell".equals(context.getDecision().getTool())) {
            JsonNode toolInput = context.getDecision().getInput();
            String command = toolInput != null && toolInput.has("command") ? toolInput.get("command").asText() : null;
            if (command != null && !"null".equals(command) && context.findMatchingGrant(command) != null) {
                return NodeResult.next(AgentNodeNames.TOOL_DISPATCH, List.of());
            }
        }
        String mode = StringUtils.defaultString(properties.getPermissionMode(), "SANDBOX").toUpperCase();
        if ("BYPASS".equals(mode)) {
            return NodeResult.next(AgentNodeNames.TOOL_DISPATCH, List.of());
        }
        if ("ACCEPT_EDITS".equals(mode)) {
            if (policy.getPermissionLevel() == ToolPermissionLevel.WRITE_CONFIRM) {
                return NodeResult.next(AgentNodeNames.TOOL_DISPATCH, List.of());
            }
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

    private NodeResult validationFailure(AgentContext context, ToolPolicyDecision policy) {
        return validationFailure(context, ToolResult.failure(
                policy.getValidationErrorCode(), policy.getValidationMessage(), 0L));
    }

    private NodeResult validationFailure(AgentContext context, ToolResult result) {
        context.runtime().advanceStep();
        context.setToolResult(result);

        List<AgentEvent> events = new ArrayList<>();
        events.add(event(context, AgentEventType.THOUGHT)
                .step(context.getStep())
                .tool(context.getDecision().getTool())
                .input(context.getDecision().getInputView())
                .workspace(context.getWorkspaceDisplayName())
                .build());
        events.add(event(context, AgentEventType.TOOL_CALL)
                .step(context.getStep())
                .tool(context.getDecision().getTool())
                .input(context.getDecision().getInputView())
                .workspace(context.getWorkspaceDisplayName())
                .build());
        return NodeResult.next(AgentNodeNames.OBSERVATION, events);
    }

    public ToolResult planGuardFailure(AgentContext context) {
        AgentRuntimeProperties.ExecutionGuardProperties guards =
                properties.getExecutionGuards();
        if (guards == null || !Boolean.TRUE.equals(guards.getPlanBeforeWrite())) {
            return null;
        }
        if (StringUtils.isNotBlank(context.getParentRunId())
                || !ToolOperation.isWorkspaceWrite(context.getDecision().getTool())) {
            return null;
        }
        if (!context.isCodeReadObserved()) {
            return ToolResult.failure(
                    "plan_required_before_write",
                    "写入前必须先读取或搜索相关代码，再用 todo_write 建立计划",
                    0L);
        }
        List<String> targets = ToolOperation.inputPaths(
                context.getDecision().getInput());
        if (context.getPlan() == null || targets.isEmpty()) {
            return ToolResult.failure(
                    "plan_update_required",
                    "写入前计划必须包含 kind=edit、targets=" + targets
                            + "，并包含 kind=verify 的测试项",
                    0L);
        }
        if (!targets.stream().allMatch(
                target -> context.getPlan().hasActiveEditTarget(target))) {
            List<AgentPlanItem> incompleteEditItems = context.getPlan().getItems().stream()
                    .filter(item -> "edit".equalsIgnoreCase(item.getKind()))
                    .filter(AgentPlanItem::incomplete)
                    .collect(Collectors.toList());
            if (incompleteEditItems.isEmpty()) {
                return ToolResult.failure(
                        "plan_update_required",
                        "写入前计划必须有 in_progress 的 kind=edit 项包含 targets=" + targets,
                        0L);
            }
            if (context.getPlan().activeEditItem() == null) {
                return ToolResult.failure(
                        "plan_update_required",
                        "存在多个编辑任务项，请先用 todo_write 明确当前 in_progress 的 edit 项",
                        0L);
            }
        }
        if (!context.getPlan().hasVerifyItem()) {
            return ToolResult.failure(
                    "plan_update_required",
                    "写入前计划必须包含 kind=edit、targets=" + targets
                            + "，并包含 kind=verify 的测试项",
                    0L);
        }
        return null;
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
                .policyFingerprint(policy.getPolicyFingerprint())
                .metadata(policy.getMetadata())
                .createdAt(now)
                .expiresAt(now.plusSeconds(Math.max(1L, properties.getApprovalTtlSeconds())))
                .context(context)
                .approvalScope("ONCE")
                .approvedPattern(null)
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
                .metadata(policy.getMetadata())
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
        context.runtime().advanceStep();
        String reason = StringUtils.defaultIfBlank(policy.getRiskReason(), "高危动作已被策略拦截");
        ToolResult result = ToolResult.failure("policy_denied", reason, 0L);
        if (policy.getMetadata() != null && !policy.getMetadata().isEmpty()) {
            result.setDetails(policy.getMetadata());
        }
        context.setToolResult(result);

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
                .metadata(policy.getMetadata())
                .build());
        return NodeResult.next(AgentNodeNames.OBSERVATION, events);
    }

}
