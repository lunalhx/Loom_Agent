package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.service.ToolExecutor;
import org.apache.commons.codec.digest.DigestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Executes one tool through the single {@link ToolExecutor} boundary.
 * Does not count tool steps (the {@link DecisionNode} already did) and
 * carries no checkpoint hook. The executor applies allowlist, validation,
 * repeated-call, readOnly/approval, snapshot/diff and output clipping.
 */
public class ToolDispatchNode extends AbstractAgentNode {

    private final ToolExecutor toolExecutor;
    private final AgentRuntimeProperties properties;

    public ToolDispatchNode(ToolExecutor toolExecutor,
                            AgentRuntimeProperties properties) {
        super(AgentNodeNames.TOOL_DISPATCH, List.of("decision.tool", "decision.input"));
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        AgentDecision decision = context.getDecision();
        ToolCall toolCall = ToolCall.builder()
                .name(decision.getTool())
                .toolCallId(toolCallId(context, decision))
                .input(decision.getInput())
                .workspace(context.getWorkspace())
                .workspaceRoot(context.getResolvedWorkspace())
                .runId(context.getRunId())
                .rootRunId(context.getRootRunId())
                .conversationId(context.getConversationId())
                .runtimeProperties(context.runtimeProperties(properties))
                .build();

        ToolExecutor.ToolRuntimePolicy policy = resolvePolicy(context);
        ToolResult result = toolExecutor.execute(context, toolCall, policy);

        List<AgentEvent> events = new ArrayList<>();
        events.add(event(context, AgentEventType.THOUGHT)
                .thought(decision.getReason())
                .build());
        events.add(event(context, AgentEventType.TOOL_CALL)
                .thought(decision.getReason())
                .tool(decision.getTool())
                .toolCallId(toolCall.getToolCallId())
                .input(decision.getInputView())
                .workspace(context.getWorkspaceDisplayName())
                .build());
        return NodeResult.nextNode(AgentNodeNames.OBSERVATION, events);
    }

    private ToolExecutor.ToolRuntimePolicy resolvePolicy(AgentContext context) {
        int depth = context.getAgentDepth();
        int maxDepth = properties.getSubAgentMaxDepth() == null ? 1 : properties.getSubAgentMaxDepth();
        Set<String> allowedTools = context.getAllowedTools() == null
                ? null : Set.copyOf(context.getAllowedTools());
        // delegate is only visible while depth < maxDepth; even if the model
        // calls it directly, the executor must reject it at depth limit.
        if (allowedTools != null && allowedTools.contains("delegate") && depth >= maxDepth) {
            allowedTools = new java.util.HashSet<>(allowedTools);
            allowedTools.remove("delegate");
        }
        ToolExecutor.ApprovalPolicy approvalPolicy = switch (context.getApprovalPolicy() == null
                ? "ask" : context.getApprovalPolicy().toLowerCase()) {
            case "auto" -> ToolExecutor.ApprovalPolicy.AUTO;
            case "never" -> ToolExecutor.ApprovalPolicy.NEVER;
            default -> ToolExecutor.ApprovalPolicy.ASK;
        };
        boolean readOnly = context.getParentRunId() != null;
        return new ToolExecutor.ToolRuntimePolicy(allowedTools, readOnly, approvalPolicy, depth, maxDepth);
    }

    private String toolCallId(AgentContext context, AgentDecision decision) {
        String input = decision.getInput() == null ? "" : decision.getInput().toString();
        return DigestUtils.sha256Hex(
                context.getRunId() + "|" + context.getToolSteps()
                        + "|" + decision.getTool() + "|" + input)
                .substring(0, 24);
    }
}
