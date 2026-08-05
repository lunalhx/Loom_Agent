package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolInputValidationResult;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single tool-execution boundary mirroring loom-code {@code ToolExecutor}.
 *
 * <p>Fixed order of checks:
 * <ol>
 *   <li>allowedTools allowlist</li>
 *   <li>tool existence</li>
 *   <li>argument parse + semantic validation (before approval)</li>
 *   <li>consecutive repeated call (3rd identical call rejected)</li>
 *   <li>readOnly and approval policy</li>
 *   <li>workspace snapshot before risky tools</li>
 *   <li>execute tool</li>
 *   <li>clip output to 4000 chars</li>
 *   <li>snapshot + diff after risky tools</li>
 *   <li>memory / process note update</li>
 *   <li>unified metadata + observation event</li>
 * </ol>
 *
 * <p>No node or delegate may call the registry directly; this is the only
 * execution entry point.
 */
public class ToolExecutor {

    public static final int MAX_TOOL_OUTPUT = 4000;

    public enum ApprovalPolicy { ASK, AUTO, NEVER }

    /** Runtime policy for a single tool call. */
    public record ToolRuntimePolicy(Set<String> allowedTools,
                                    boolean readOnly,
                                    ApprovalPolicy approvalPolicy,
                                    int depth,
                                    int maxDepth) {
        public static ToolRuntimePolicy root(Set<String> allowedTools, ApprovalPolicy policy) {
            return new ToolRuntimePolicy(allowedTools, false, policy, 0, 1);
        }

        public static ToolRuntimePolicy delegateChild(Set<String> allowedTools) {
            return new ToolRuntimePolicy(allowedTools, true, ApprovalPolicy.NEVER, 1, 1);
        }
    }

    /** CLI-facing approval prompt: shows tool name + full args, y/yes allows. */
    public interface ApprovalPrompt {
        boolean ask(String toolName, JsonNode args);
    }

    private final ToolRegistry registry;
    private final ApprovalPrompt approvalPrompt;

    public ToolExecutor(ToolRegistry registry, ApprovalPrompt approvalPrompt) {
        this.registry = registry;
        this.approvalPrompt = approvalPrompt;
    }

    public ToolExecutor(ToolRegistry registry) {
        this(registry, null);
    }

    /**
     * Execute one tool call with full governance. Tool history for the
     * repeated-call check is maintained on the context; rejected calls are
     * also recorded so subsequent checks stay consistent.
     */
    public ToolResult execute(AgentContext context, ToolCall call, ToolRuntimePolicy policy) {
        String name = call.getName();
        JsonNode args = normalizeArgs(call);

        // 1. allowedTools
        if (policy.allowedTools() != null && !policy.allowedTools().contains(name)) {
            return rejected(context, call, "tool_not_allowed",
                    "error: tool '" + name + "' is not allowed in this run",
                    "rejected", "high", false, "tool_not_allowed");
        }

        // 2. tool existence
        if (!registry.contains(name)) {
            return rejected(context, call, "unknown_tool",
                    "error: unknown tool '" + name + "'",
                    "rejected", "high", false, "unknown_tool");
        }

        boolean risky = registry.isRisky(name);

        // 3. argument parse + semantic validation (before approval)
        ToolInputValidationResult validation = registry.validateInput(name, args);
        if (!validation.valid()) {
            String detail = validation.errors().stream()
                    .map(e -> e.pointer() + ": " + e.message())
                    .reduce((a, b) -> a + "; ").orElse("invalid");
            String example = exampleFor(name);
            String message = "error: invalid arguments for " + name + ": " + detail
                    + (example.isEmpty() ? "" : "\nexample: " + example);
            return rejected(context, call, "invalid_arguments",
                    message, "rejected", risky ? "high" : "low", !risky, "invalid_arguments");
        }

        // 4. consecutive repeated call
        if (isRepeated(context, call, name, args)) {
            return rejected(context, call, "repeated_identical_call",
                    "error: repeated identical tool call for " + name
                            + "; choose a different tool or return a final answer",
                    "rejected", risky ? "high" : "low", !risky, "repeated_identical_call");
        }

        // 5. readOnly and approval policy
        if (policy.readOnly() && risky) {
            return rejected(context, call, "approval_denied",
                    "error: approval denied for " + name + " (read-only run)",
                    "rejected", "high", false, "read_only_block");
        }
        if (risky && !isApproved(policy, name, args)) {
            return rejected(context, call, "approval_denied",
                    "error: approval denied for " + name,
                    "rejected", "high", false, "approval_denied");
        }

        // 6. snapshot before risky tools
        Path root = call.getWorkspaceRoot();
        Map<String, String> before = risky ? WorkspaceFingerprint.snapshot(root) : Map.of();
        Map<String, String> after = before;
        try {
            // 7. execute
            ToolResult result = registry.call(call);
            // 8. clip output
            String clipped = clip(result.getObservation());
            result.setObservation(clipped);
            result.setTruncated(clipped.length() < (result.getObservation() == null ? 0 : result.getObservation().length()));
            // 9. snapshot + diff after risky tools
            after = risky ? WorkspaceFingerprint.snapshot(root) : before;
            WorkspaceFingerprint.DiffResult diff = WorkspaceFingerprint.diff(before, after);
            boolean workspaceChanged = !diff.affectedPaths().isEmpty();

            String toolStatus = "ok";
            String toolErrorCode = "";
            if ("run_shell".equals(name)) {
                int exitCode = parseExitCode(clipped);
                if (exitCode != 0 && workspaceChanged) {
                    toolStatus = "partial_success";
                    toolErrorCode = "tool_partial_success";
                } else if (exitCode != 0) {
                    toolStatus = "error";
                    toolErrorCode = "tool_failed";
                }
            }
            applyMetadata(result, toolStatus, toolErrorCode, risky, diff, workspaceChanged, root);
            // 10. memory / process note
            context.setToolResult(result);
            recordToolEvent(context, call, name, args);
            return result;
        } catch (Exception e) {
            after = risky ? WorkspaceFingerprint.snapshot(root) : before;
            WorkspaceFingerprint.DiffResult diff = WorkspaceFingerprint.diff(before, after);
            boolean workspaceChanged = !diff.affectedPaths().isEmpty();
            String securityEvent = e.getMessage() != null && e.getMessage().contains("path escapes workspace")
                    ? "path_escape" : "";
            ToolResult result = ToolResult.failure(
                    workspaceChanged ? "tool_partial_success" : "tool_failed",
                    "error: tool " + name + " failed: " + e.getMessage(), 0L);
            result.setToolStatus(workspaceChanged ? "partial_success" : "error");
            result.setToolErrorCode(workspaceChanged ? "tool_partial_success" : "tool_failed");
            result.setSecurityEventType(securityEvent);
            result.setRiskLevel(risky ? "high" : "low");
            result.setReadOnly(!risky);
            result.setAffectedPaths(diff.affectedPaths());
            result.setWorkspaceChanged(workspaceChanged);
            result.setDiffSummary(diff.diffSummary());
            result.setObservation(clip(result.getObservation()));
            context.setToolResult(result);
            recordToolEvent(context, call, name, args);
            return result;
        }
    }

    private void recordToolEvent(AgentContext context, ToolCall call, String name, JsonNode args) {
        if (context.getHistory() != null) {
            List<cn.lunalhx.ai.domain.agent.model.entity.AgentStep> history =
                    new ArrayList<>(context.getHistory());
            history.add(cn.lunalhx.ai.domain.agent.model.entity.AgentStep.builder()
                    .toolStep(history.size() + 1)
                    .tool(name)
                    .input(args == null ? null : args.toString())
                    .observation(context.getToolResult() == null ? null : context.getToolResult().getObservation())
                    .success(context.getToolResult() != null && context.getToolResult().isSuccess())
                    .build());
            context.setHistory(history);
        }
    }

    private boolean isRepeated(AgentContext context, ToolCall call, String name, JsonNode args) {
        List<cn.lunalhx.ai.domain.agent.model.entity.AgentStep> history = context.getHistory();
        if (history == null || history.size() < 2) {
            return false;
        }
        List<cn.lunalhx.ai.domain.agent.model.entity.AgentStep> recent = history.subList(history.size() - 2, history.size());
        return recent.stream().allMatch(step -> name.equals(step.getTool())
                && sameArgs(step.getInput(), args == null ? null : args.toString()));
    }

    private boolean sameArgs(String a, String b) {
        if (a == null && b == null) {
            return true;
        }
        return a != null && a.equals(b);
    }

    private boolean isApproved(ToolRuntimePolicy policy, String name, JsonNode args) {
        if (policy.approvalPolicy() == ApprovalPolicy.AUTO) {
            return true;
        }
        if (policy.approvalPolicy() == ApprovalPolicy.NEVER) {
            return false;
        }
        if (approvalPrompt == null) {
            return false;
        }
        return approvalPrompt.ask(name, args);
    }

    private JsonNode normalizeArgs(ToolCall call) {
        JsonNode args = call.getInput();
        if (args == null || args.isMissingNode() || args.isNull()) {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.createObjectNode();
        }
        if (!args.isObject()) {
            return args;
        }
        // sort keys for normalized comparison (repeated-call + metadata)
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode sorted = mapper.createObjectNode();
        List<String> keys = new ArrayList<>();
        args.fieldNames().forEachRemaining(keys::add);
        keys.sort(Comparator.naturalOrder());
        for (String key : keys) {
            sorted.set(key, args.get(key));
        }
        return sorted;
    }

    private ToolResult rejected(AgentContext context, ToolCall call, String errorCode,
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
        context.setToolResult(result);
        recordToolEvent(context, call, call.getName(), normalizeArgs(call));
        return result;
    }

    private void applyMetadata(ToolResult result, String toolStatus, String toolErrorCode,
                               boolean risky, WorkspaceFingerprint.DiffResult diff,
                               boolean workspaceChanged, Path root) {
        result.setToolStatus(toolStatus);
        result.setToolErrorCode(toolErrorCode);
        result.setRiskLevel(risky ? "high" : "low");
        result.setReadOnly(!risky);
        result.setAffectedPaths(diff.affectedPaths());
        result.setWorkspaceChanged(workspaceChanged);
        result.setDiffSummary(diff.diffSummary());
        if (root != null) {
            result.setWorkspaceFingerprint(WorkspaceFingerprint.snapshot(root).hashCode() + "");
        }
    }

    private int parseExitCode(String content) {
        if (content == null) {
            return 0;
        }
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("exit_code:\\s*(-?\\d+)").matcher(content);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }

    private String clip(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_TOOL_OUTPUT) {
            return text;
        }
        return text.substring(0, MAX_TOOL_OUTPUT)
                + "\n...[truncated " + (text.length() - MAX_TOOL_OUTPUT) + " chars]";
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
