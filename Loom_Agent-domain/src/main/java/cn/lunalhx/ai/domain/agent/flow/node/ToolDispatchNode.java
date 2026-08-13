package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.DelegateRequest;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.ToolOutputSanitization;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.service.ToolApprovalResolver;
import cn.lunalhx.ai.domain.tool.service.ToolAuthorizationResult;
import cn.lunalhx.ai.domain.tool.service.ToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.DigestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Tool input governance node ({@code tool_input}). Constructs the normalized
 * {@link ToolCall} and runs {@link ToolApprovalResolver} over the decision-only
 * authorization service — allowlist, existence, schema validation, repeated-call,
 * read-only and approval. Rejected calls produce a unified safe {@link ToolResult}
 * and route to {@code tool_output}; accepted calls route to {@code tool_execute}.
 *
 * <p>Two input expressions exist: the raw execution value (inside the
 * {@link ToolCall}, never persisted) and the redacted audit/display value
 * (written into the decision's {@code input}/{@code inputView}, events, state
 * and checkpoint). This node never executes tools and never writes tool
 * output.
 */
public class ToolDispatchNode extends AbstractAgentNode {

    private static final ObjectMapper REDACTION_MAPPER = new ObjectMapper();

    private final ToolApprovalResolver approvalResolver;
    private final ToolOutputSanitizer sanitizer;
    private final AgentRuntimeProperties properties;

    public ToolDispatchNode(ToolApprovalResolver approvalResolver,
                            ToolOutputSanitizer sanitizer,
                            AgentRuntimeProperties properties) {
        super(AgentNodeNames.TOOL_INPUT, List.of("decision.tool", "decision.input"));
        this.approvalResolver = Objects.requireNonNull(approvalResolver, "approvalResolver must not be null");
        this.sanitizer = sanitizer;
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        AgentDecision decision = context.getDecision();
        String rawInputText = decision.getInput() == null ? "" : decision.getInput().toString();
        ToolCall toolCall = ToolCall.builder()
                .name(decision.getTool())
                .toolCallId(toolCallId(context, decision, rawInputText))
                .input(decision.getInput())
                .workspace(context.getWorkspace())
                .workspaceRoot(context.getResolvedWorkspace())
                .runId(context.getRunId())
                .rootRunId(context.getRootRunId())
                .conversationId(context.getConversationId())
                .collaborationMode(context.getCollaborationMode())
                .runtimeProperties(context.runtimeProperties(properties))
                .secretEnvNames(context.runtimeProperties(properties).getSecretEnvNames() == null
                        ? null : Set.copyOf(context.runtimeProperties(properties).getSecretEnvNames()))
                .recentSummary(context.workingMemoryOrCreate().taskSummary())
                .securityScope(context.getSecurityScope())
                .activeSkills(context.getActiveSkills())
                .leaseFence(context.getLeaseFence())
                .build();

        ToolExecutor.ToolRuntimePolicy policy = resolvePolicy(context);
        // Redact the durable decision before approval I/O so a pending-approval
        // checkpoint cannot persist raw secret parameters.
        applyRedactedInput(decision, toolCall.getName());
        ToolAuthorizationResult authorization = approvalResolver.resolve(context, toolCall, policy,
                context.getPermissionPolicySnapshot());

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
        if (!authorization.authorized()) {
            context.setToolResult(authorization.rejection());
            return NodeResult.nextNode(AgentNodeNames.TOOL_OUTPUT, events);
        }
        if ("delegate".equals(toolCall.getName())) {
            cn.lunalhx.ai.domain.tool.model.OutboundDisclosure disclosure =
                    toolCall.getEffectProfile() == null
                            ? cn.lunalhx.ai.domain.tool.model.OutboundDisclosure.NONE
                            : toolCall.getEffectProfile().outboundDisclosure();
            toolCall.setDelegateRequest(DelegateRequest.fromParent(
                    context, context.runtimeProperties(properties), disclosure));
            authorization = ToolAuthorizationResult.authorized(
                    authorization.authorizedCall().withDelegateRequest(toolCall.getDelegateRequest()));
        }
        context.setToolCall(toolCall);
        context.setAuthorizedToolCall(authorization.authorizedCall());
        return NodeResult.nextNode(AgentNodeNames.TOOL_EXECUTE, events);
    }

    /** Replace decision input with the redacted display value (fail-closed:
     *  never fall back to raw input on sanitizer failure). */
    private void applyRedactedInput(AgentDecision decision, String toolName) {
        String raw = decision.getInput() == null ? "{}" : decision.getInput().toString();
        String safe;
        if (sanitizer == null) {
            safe = raw;
        } else {
            try {
                safe = sanitizer.sanitize(toolName, raw).getOutput();
            } catch (Exception e) {
                safe = "<redacted>";
            }
        }
        JsonNode safeNode;
        try {
            safeNode = REDACTION_MAPPER.readTree(safe);
        } catch (Exception e) {
            safeNode = REDACTION_MAPPER.createObjectNode();
        }
        decision.setInput(safeNode);
        decision.setInputView(REDACTION_MAPPER.convertValue(
                safeNode, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}));
    }

    private ToolExecutor.ToolRuntimePolicy resolvePolicy(AgentContext context) {
        int depth = context.getAgentDepth();
        int maxDepth = properties.getSubAgentMaxDepth() == null ? 1 : properties.getSubAgentMaxDepth();
        Set<String> allowedTools = context.getAllowedTools() == null
                ? null : Set.copyOf(context.getAllowedTools());
        // delegate is only visible while depth < maxDepth; even if the model
        // calls it directly, the executor must reject it at depth limit.
        if (depth >= maxDepth) {
            allowedTools = allowedTools == null
                    ? context.getToolSpecs().stream()
                    .map(cn.lunalhx.ai.domain.tool.model.ToolSpec::getName)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new))
                    : new java.util.HashSet<>(allowedTools);
            allowedTools.remove("delegate");
        }
        return new ToolExecutor.ToolRuntimePolicy(
                allowedTools,
                context.getCollaborationMode(),
                depth,
                maxDepth,
                context.getExecutionProfile());
    }

    private String toolCallId(AgentContext context, AgentDecision decision, String rawInput) {
        String input = rawInput == null ? "" : rawInput;
        return DigestUtils.sha256Hex(
                context.getRunId() + "|" + context.getAttemptId() + "|" + context.getToolSteps()
                        + "|" + decision.getTool() + "|" + input)
                .substring(0, 24);
    }
}
