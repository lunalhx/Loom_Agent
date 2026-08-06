package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentStep;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolInputValidationResult;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Input-governance service of the tool chain. Sole owner of:
 * <ul>
 *   <li>allowedTools allowlist</li>
 *   <li>tool existence</li>
 *   <li>argument parse + semantic validation (before approval)</li>
 *   <li>consecutive repeated call (3rd identical call rejected)</li>
 *   <li>readOnly and approval policy</li>
 * </ul>
 *
 * <p>Rejected calls produce a unified safe {@link ToolResult}; the raw input
 * is never written to state (only a sanitized/redacted display value is).
 *
 * <p>This gate never executes tools and never writes output.
 */
public class ToolInputGate {

    private final ToolRegistry registry;
    private final ToolExecutor.ApprovalPrompt approvalPrompt;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Optional trace recorder for approval security events. */
    private java.util.function.BiConsumer<AgentContext, ApprovalAudit> auditSink;

    /** Approval audit event: only safe parameter summaries, never raw input. */
    public record ApprovalAudit(String eventType, String toolName, Map<String, Object> argSummary) {
    }

    public ToolInputGate(ToolRegistry registry, ToolExecutor.ApprovalPrompt approvalPrompt) {
        this.registry = registry;
        this.approvalPrompt = approvalPrompt;
    }

    public ToolInputGate(ToolRegistry registry) {
        this(registry, null);
    }

    /** Wire an audit sink for {@code approval_requested}/{@code approval_granted}/
     *  {@code approval_denied}/{@code approval_blocked_by_read_only} events. */
    public ToolInputGate withAuditSink(java.util.function.BiConsumer<AgentContext, ApprovalAudit> sink) {
        this.auditSink = sink;
        return this;
    }

    /** Run the fixed governance order; returns {@code null} when accepted. */
    public ToolResult evaluate(AgentContext context, ToolCall call,
                               ToolExecutor.ToolRuntimePolicy policy) {
        String name = call.getName();
        JsonNode args = normalizeArgs(call);
        boolean risky = registry.isRisky(name);

        // 1. allowedTools
        if (policy.allowedTools() != null && !policy.allowedTools().contains(name)) {
            return rejected(call, "tool_not_allowed",
                    "error: tool '" + name + "' is not allowed in this run",
                    "rejected", "high", false, "tool_not_allowed");
        }

        // 2. tool existence
        if (!registry.contains(name)) {
            return rejected(call, "unknown_tool",
                    "error: unknown tool '" + name + "'",
                    "rejected", "high", false, "unknown_tool");
        }

        // 3. argument parse + semantic validation (before approval)
        ToolInputValidationResult validation = registry.validateInput(name, args);
        if (!validation.valid()) {
            String detail = validation.errors().stream()
                    .map(e -> e.pointer() + ": " + e.message())
                    .reduce((a, b) -> a + "; ").orElse("invalid");
            String example = exampleFor(name);
            String message = "error: invalid arguments for " + name + ": " + detail
                    + (example.isEmpty() ? "" : "\nexample: " + example);
            return rejected(call, "invalid_arguments",
                    message, "rejected", risky ? "high" : "low", !risky, "invalid_arguments");
        }

        // 4. consecutive repeated call
        if (isRepeated(context, call, name, args)) {
            return rejected(call, "repeated_identical_call",
                    "error: repeated identical tool call for " + name
                            + "; choose a different tool or return a final answer",
                    "rejected", risky ? "high" : "low", !risky, "repeated_identical_call");
        }

        // 5. readOnly and approval policy
        Map<String, Object> argSummary = ApprovalDisplay.summarize(args);
        if (policy.readOnly() && risky) {
            audit(context, "approval_blocked_by_read_only", name, argSummary);
            return rejected(call, "approval_denied",
                    "error: approval denied for " + name + " (read-only run)",
                    "rejected", "high", false, "read_only_block");
        }
        if (risky && !isApproved(policy, name, args)) {
            audit(context, "approval_denied", name, argSummary);
            return rejected(call, "approval_denied",
                    "error: approval denied for " + name,
                    "rejected", "high", false, "approval_denied");
        }
        audit(context, "approval_granted", name, argSummary);
        return null;
    }

    private void audit(AgentContext context, String eventType, String toolName,
                       Map<String, Object> argSummary) {
        if (auditSink != null) {
            auditSink.accept(context, new ApprovalAudit(eventType, toolName, argSummary));
        }
    }

    private boolean isRepeated(AgentContext context, ToolCall call, String name, JsonNode args) {
        List<AgentStep> history = context.getHistory();
        if (history == null || history.size() < 2) {
            return false;
        }
        List<AgentStep> recent = history.subList(history.size() - 2, history.size());
        return recent.stream().allMatch(step -> name.equals(step.getTool())
                && sameArgs(step.getInput(), args == null ? null : args.toString()));
    }

    private boolean sameArgs(String a, String b) {
        if (a == null && b == null) {
            return true;
        }
        return a != null && a.equals(b);
    }

    private boolean isApproved(ToolExecutor.ToolRuntimePolicy policy, String name, JsonNode args) {
        if (policy.approvalPolicy() == ToolExecutor.ApprovalPolicy.AUTO) {
            return true;
        }
        if (policy.approvalPolicy() == ToolExecutor.ApprovalPolicy.NEVER) {
            return false;
        }
        if (approvalPrompt == null) {
            return false;
        }
        // Approval displays only the summarized view (path + length + hash),
        // never full command/content or secret values.
        return approvalPrompt.ask(name, mapper.valueToTree(ApprovalDisplay.summarize(args)));
    }

    private ToolResult rejected(ToolCall call, String errorCode,
                                String content, String toolStatus, String riskLevel,
                                boolean readOnly, String securityEvent) {
        ToolResult result = ToolResult.failure(errorCode, content, 0L);
        result.setToolStatus("rejected");
        result.setToolErrorCode(errorCode);
        result.setSecurityEventType(securityEvent);
        result.setRiskLevel(riskLevel);
        result.setReadOnly(readOnly);
        result.setAffectedPaths(List.of());
        result.setWorkspaceChanged(false);
        result.setDiffSummary(List.of());
        result.setObservation(clip(content));
        return result;
    }

    private JsonNode normalizeArgs(ToolCall call) {
        JsonNode args = call.getInput();
        if (args == null || args.isMissingNode() || args.isNull()) {
            return mapper.createObjectNode();
        }
        if (!args.isObject()) {
            return args;
        }
        // sort keys for normalized comparison (repeated-call + metadata)
        ObjectNode sorted = mapper.createObjectNode();
        List<String> keys = new ArrayList<>();
        args.fieldNames().forEachRemaining(keys::add);
        keys.sort(Comparator.naturalOrder());
        for (String key : keys) {
            sorted.set(key, args.get(key));
        }
        return sorted;
    }

    private String clip(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= ToolExecutor.MAX_TOOL_OUTPUT) {
            return text;
        }
        return text.substring(0, ToolExecutor.MAX_TOOL_OUTPUT)
                + "\n...[truncated " + (text.length() - ToolExecutor.MAX_TOOL_OUTPUT) + " chars]";
    }

    private String exampleFor(String name) {
        return switch (name) {
            case "list_files" -> "<tool>{\"name\":\"list_files\",\"args\":{\"path\":\".\"}}</tool>";
            case "read_file" -> "<tool>{\"name\":\"read_file\",\"args\":{\"path\":\"README.md\",\"start\":1,\"end\":80}}</tool>";
            case "search" -> "<tool>{\"name\":\"search\",\"args\":{\"pattern\":\"binary_search\",\"path\":\".\"}}</tool>";
            case "run_shell" -> "<tool>{\"name\":\"run_shell\",\"args\":{\"command\":\"mvn -q test\",\"timeout\":20}}</tool>";
            case "write_file" -> "<tool name=\"write_file\" path=\"a.py\"><content>...</content></tool>";
            case "patch_file" -> "<tool name=\"patch_file\" path=\"a.py\"><old_text>old</old_text><new_text>new</new_text></tool>";
            case "delegate" -> "<tool>{\"name\":\"delegate\",\"args\":{\"task\":\"inspect README.md\",\"max_steps\":3}}</tool>";
            default -> "";
        };
    }
}
