package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentStep;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ApprovalRequirement;
import cn.lunalhx.ai.domain.tool.model.CallEffectAssessment;
import cn.lunalhx.ai.domain.tool.model.EffectProfile;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
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
import java.util.Objects;

/**
 * Pre-execution governance for tool calls. It owns the base allowlist,
 * catalog existence, schema validation, repeated-call guard, effect boundary,
 * and independent approval requirement. It never executes a tool.
 */
public class ToolInputGate {

    private final ToolRegistry registry;
    private final ToolExecutor.ApprovalPrompt approvalPrompt;
    private final ObjectMapper mapper = new ObjectMapper();
    private java.util.function.BiConsumer<AgentContext, ApprovalAudit> auditSink;

    public record ApprovalAudit(String eventType, String toolName, Map<String, Object> argSummary) {
    }

    public ToolInputGate(ToolRegistry registry, ToolExecutor.ApprovalPrompt approvalPrompt) {
        this.registry = registry;
        this.approvalPrompt = approvalPrompt;
    }

    public ToolInputGate(ToolRegistry registry) {
        this(registry, null);
    }

    public ToolInputGate withAuditSink(java.util.function.BiConsumer<AgentContext, ApprovalAudit> sink) {
        this.auditSink = sink;
        return this;
    }

    /** Returns null only when the call is authorized and may execute. */
    public ToolResult evaluate(AgentContext context, ToolCall call,
                               ToolExecutor.ToolRuntimePolicy policy) {
        String name = call.getName();
        JsonNode args = normalizeArgs(call);
        CollaborationMode mode = Objects.requireNonNull(context.getCollaborationMode(),
                "run collaboration mode must not be null");
        boolean delegateRun = context.getParentRunId() != null || context.getAgentDepth() > 0;
        ExecutionProfile executionProfile = ExecutionProfile.forRun(mode, delegateRun);

        if (policy.allowedTools() != null && !policy.allowedTools().contains(name)) {
            return rejected(call, "tool_not_allowed",
                    "error: tool '" + name + "' is not allowed in this run",
                    "tool_not_allowed", EffectProfile.unknown());
        }

        // Catalog projection is only a model hint in Build; in Plan it is also
        // re-derived here so hidden/stale/fabricated calls cannot widen access.
        if (mode == CollaborationMode.PLAN
                && !registry.isPlanVisible(name, policy.allowedTools())) {
            return rejected(call, "plan_mode_denied",
                    "error: tool '" + name + "' is not available in Plan Mode",
                    "plan_mode_denied", EffectProfile.unknown());
        }

        if (!registry.contains(name)) {
            return rejected(call, "unknown_tool",
                    "error: unknown tool '" + name + "'",
                    "unknown_tool", EffectProfile.unknown());
        }

        ToolInputValidationResult validation = registry.validateInput(name, args);
        if (!validation.valid()) {
            String detail = validation.errors().stream()
                    .map(e -> e.pointer() + ": " + e.message())
                    .reduce((a, b) -> a + "; ").orElse("invalid");
            String example = exampleFor(name);
            String message = "error: invalid arguments for " + name + ": " + detail
                    + (example.isEmpty() ? "" : "\nexample: " + example);
            return rejected(call, "invalid_arguments", message,
                    "invalid_arguments", EffectProfile.unknown());
        }

        call.setInput(args);
        if (isRepeated(context, name, args)) {
            return rejected(call, "repeated_identical_call",
                    "error: repeated identical tool call for " + name
                            + "; choose a different tool or return a final answer",
                    "repeated_identical_call", EffectProfile.unknown());
        }

        CallEffectAssessment assessment = registry.assessEffect(
                name, call, executionProfile);
        EffectProfile effectProfile = assessment.profile();
        boolean restrictedRun = mode == CollaborationMode.PLAN || delegateRun;
        if (restrictedRun && (!assessment.trusted()
                || !executionProfile.allows(effectProfile))) {
            audit(context, "plan_mode_denied", name, ApprovalDisplay.summarize(args));
            return rejected(call, "plan_mode_denied",
                    "error: effect profile is not authorized for this run",
                    "plan_mode_denied", effectProfile);
        }

        ApprovalRequirement requirement = registry.approvalRequirement(name);
        call.setEffectProfile(effectProfile);
        call.setApprovalRequired(requirement.required());
        Map<String, Object> argSummary = ApprovalDisplay.summarize(args);
        if (requirement.required() && !isApproved(policy, name, args)) {
            audit(context, "approval_denied", name, argSummary);
            return rejected(call, "approval_denied",
                    "error: approval denied for " + name,
                    "approval_denied", effectProfile);
        }
        audit(context, requirement.required() ? "approval_granted" : "call_authorized",
                name, argSummary);
        return null;
    }

    private void audit(AgentContext context, String eventType, String toolName,
                       Map<String, Object> argSummary) {
        if (auditSink != null) {
            auditSink.accept(context, new ApprovalAudit(eventType, toolName, argSummary));
        }
    }

    private boolean isRepeated(AgentContext context, String name, JsonNode args) {
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
        return approvalPrompt.ask(name, mapper.valueToTree(ApprovalDisplay.summarize(args)));
    }

    private ToolResult rejected(ToolCall call, String errorCode,
                                String content, String securityEvent,
                                EffectProfile effectProfile) {
        ToolResult result = ToolResult.failure(errorCode, content, 0L);
        result.setToolStatus("rejected");
        result.setToolErrorCode(errorCode);
        result.setSecurityEventType(securityEvent);
        result.setEffectProfile(effectProfile);
        result.setApprovalRequired(call.isApprovalRequired());
        result.setReadOnly(effectProfile != null && effectProfile.isReadOnly());
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
