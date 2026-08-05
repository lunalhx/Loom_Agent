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
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalRecordState;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Approval gate for risky tools driven by {@code approvalPolicy} ("ask"|"auto"|"never").
 * Only handles risk policy and the approval record; recording the denial
 * observation is left to {@link ObservationNode}.
 */
public class ApprovalGateNode extends AbstractAgentNode {

    private final ToolRegistry toolRegistry;
    private final ApprovalStore approvalStore;
    private final AgentRuntimeProperties properties;

    public ApprovalGateNode(ToolRegistry toolRegistry, ApprovalStore approvalStore,
                            AgentRuntimeProperties properties) {
        super(AgentNodeNames.APPROVAL_GATE, List.of("decision.tool", "decision.input"));
        this.toolRegistry = toolRegistry;
        this.approvalStore = approvalStore;
        this.properties = properties;
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        AgentRuntimeProperties runProperties = context.runtimeProperties(properties);
        String tool = context.getDecision().getTool();
        boolean risky = toolRegistry.isRisky(tool);

        if (!risky) {
            return NodeResult.nextNode(AgentNodeNames.TOOL_DISPATCH, List.of());
        }

        String policy = StringUtils.defaultString(context.getApprovalPolicy(), "ask").toLowerCase();
        if ("auto".equals(policy)) {
            return NodeResult.nextNode(AgentNodeNames.TOOL_DISPATCH, List.of());
        }
        if ("never".equals(policy)) {
            ToolResult result = ToolResult.failure("approval_denied",
                    "error: approval denied for " + tool, 0L);
            result.setToolStatus("rejected");
            result.setToolErrorCode("approval_denied");
            result.setRiskLevel("high");
            result.setReadOnly(false);
            result.setAffectedPaths(List.of());
            result.setWorkspaceChanged(false);
            result.setDiffSummary(List.of());
            context.setToolResult(result);
            return NodeResult.nextNode(AgentNodeNames.OBSERVATION, List.of());
        }

        return requireApproval(context, tool, runProperties);
    }

    private NodeResult requireApproval(AgentContext context, String tool,
                                       AgentRuntimeProperties runProperties) {
        Instant now = Instant.now();
        String approvalId = UUID.randomUUID().toString();
        context.setPendingApprovalId(approvalId);
        Map<String, Object> inputSummary = summarizeInput(context.getDecision().getInputView());
        PendingApproval approval = PendingApproval.builder()
                .approvalId(approvalId)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .resolvedWorkspace(context.getResolvedWorkspace())
                .workspace(context.getWorkspace())
                .workspaceDisplayName(context.getWorkspaceDisplayName())
                .tool(tool)
                .input(inputSummary)
                .riskReason("RISKY")
                .operationPreview(String.valueOf(context.getDecision().getInputView()))
                .createdAt(now)
                .expiresAt(now.plusSeconds(Math.max(1L,
                        runProperties.getApprovalTtlSeconds())))
                .context(context)
                .state(ApprovalRecordState.PENDING)
                .build();
        approvalStore.save(approval);
        return NodeResult.pauseApproval(List.of(event(context, AgentEventType.APPROVAL_REQUIRED)
                .tool(tool)
                .input(inputSummary)
                .approvalId(approvalId)
                .workspace(context.getWorkspaceDisplayName())
                .riskReason("RISKY")
                .operationPreview(approval.getOperationPreview())
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
            if ("content".equals(key) || "old_text".equals(key) || "new_text".equals(key)) {
                return "<" + text.length() + " chars>";
            }
            return StringUtils.abbreviate(text, 200);
        }
        return value;
    }
}