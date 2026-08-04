package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolInputValidationResult;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.WorkspaceRef;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Structured tool execution boundary mirroring loom-code {@code ToolExecutor}.
 *
 * <p>Order of checks:
 * <ol>
 *   <li>allowedTools allowlist</li>
 *   <li>unknown tool</li>
 *   <li>JSON Schema + tool-semantic validation</li>
 *   <li>repeated identical call (third identical call rejected)</li>
 *   <li>approval policy for risky tools (ask/auto/never)</li>
 *   <li>workspace snapshot before/after for risky tools</li>
 *   <li>clip output, compute affected paths + created/modified/deleted summary</li>
 *   <li>write loom-code metadata; mark partial_success / tool_failed</li>
 * </ol>
 *
 * <p>The executor is stateless per call; repeated-call tracking and approval
 * policy are injected by the runtime.
 */
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    public enum ApprovalPolicy { ASK, AUTO, NEVER }

    public record ApprovalDecision(boolean approved, String reason) {
        public static ApprovalDecision approve() { return new ApprovalDecision(true, ""); }
        public static ApprovalDecision deny(String reason) { return new ApprovalDecision(false, reason); }
    }

    /** Resolves a tool name+args to the workspace root (for snapshot). */
    private final Function<ToolCall, Path> workspaceRootResolver;
    /** True when the call is risky and must pass approval. */
    private final Predicate<String> riskyTool;
    /** Repeated-call detector: given name+args returns true when it is the 3rd identical call. */
    private final BiPredicate<String, JsonNode> repeatedCallDetector;
    /** Approval gate for risky tools. */
    private final ApprovalGate approvalGate;

    public interface ApprovalGate {
        ApprovalDecision decide(String toolName, JsonNode args);
    }

    public ToolExecutor(Function<ToolCall, Path> workspaceRootResolver,
                        Predicate<String> riskyTool,
                        BiPredicate<String, JsonNode> repeatedCallDetector,
                        ApprovalGate approvalGate) {
        this.workspaceRootResolver = workspaceRootResolver;
        this.riskyTool = riskyTool;
        this.repeatedCallDetector = repeatedCallDetector;
        this.approvalGate = approvalGate;
    }

    public ToolResult execute(ToolRegistry registry, ToolCall call, Set<String> allowedTools,
                              ApprovalPolicy approvalPolicy) {
        String name = call.getName();
        JsonNode args = call.getInput() == null
                ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : call.getInput();

        if (allowedTools != null && !allowedTools.contains(name)) {
            return reject("tool_not_allowed", "tool '" + name + "' is not allowed in this run", "rejected",
                    "high", false, "tool_not_allowed", "");
        }
        if (!registry.contains(name)) {
            return reject("unknown_tool", "error: unknown tool '" + name + "'", "rejected",
                    "high", false, "unknown_tool", "");
        }

        boolean risky = riskyTool.test(name);
        ToolInputValidationResult validation = registry.validateInput(name, args);
        if (!validation.valid()) {
            String detail = validation.errors().stream()
                    .map(e -> e.pointer() + ": " + e.message())
                    .reduce((a, b) -> a + "; " + b).orElse("invalid");
            String example = exampleFor(name);
            String message = "error: invalid arguments for " + name + ": " + detail
                    + (example.isEmpty() ? "" : "\nexample: " + example);
            return reject("invalid_arguments", message, "rejected",
                    risky ? "high" : "low", !risky, "invalid_arguments", "");
        }

        if (repeatedCallDetector.test(name, args)) {
            return reject("repeated_identical_call",
                    "error: repeated identical tool call for " + name + "; choose a different tool or return a final answer",
                    "rejected", risky ? "high" : "low", !risky, "repeated_identical_call", "");
        }

        if (risky && !isApproved(approvalPolicy, name, args)) {
            return reject("approval_denied", "error: approval denied for " + name, "rejected",
                    "high", false, "approval_denied", "");
        }

        Map<String, String> before = risky ? snapshot(call) : Map.of();
        Map<String, String> after = before;
        try {
            ToolResult result = registry.call(call);
            String content = clip(result.getObservation());
            after = risky ? snapshot(call) : before;
            WorkspaceFingerprint.DiffResult diff = WorkspaceFingerprint.diff(before, after);
            boolean workspaceChanged = !diff.affectedPaths().isEmpty();
            result.setObservation(content);
            result.setTruncated(content.length() < (result.getObservation() == null ? 0 : result.getObservation().length()));

            String toolStatus = "ok";
            String toolErrorCode = "";
            if ("run_shell".equals(name)) {
                int exitCode = parseExitCode(content);
                if (exitCode != 0 && workspaceChanged) {
                    toolStatus = "partial_success";
                    toolErrorCode = "tool_partial_success";
                } else if (exitCode != 0) {
                    toolStatus = "error";
                    toolErrorCode = "tool_failed";
                }
            }
            applyMetadata(result, toolStatus, toolErrorCode, risky, diff, workspaceChanged);
            return result;
        } catch (Exception e) {
            log.warn("tool {} failed", name, e);
            after = risky ? snapshot(call) : before;
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
            return result;
        }
    }

    private boolean isApproved(ApprovalPolicy policy, String name, JsonNode args) {
        if (policy == ApprovalPolicy.AUTO) {
            return true;
        }
        if (policy == ApprovalPolicy.NEVER) {
            return false;
        }
        ApprovalDecision decision = approvalGate.decide(name, args);
        return decision.approved();
    }

    private ToolResult reject(String toolErrorCode, String content, String toolStatus,
                              String riskLevel, boolean readOnly, String securityEvent, String securityType) {
        ToolResult result = ToolResult.failure(toolErrorCode, content, 0L);
        result.setToolStatus("rejected");
        result.setToolErrorCode(toolErrorCode);
        result.setSecurityEventType(securityType);
        result.setRiskLevel(riskLevel);
        result.setReadOnly(readOnly);
        result.setAffectedPaths(List.of());
        result.setWorkspaceChanged(false);
        result.setDiffSummary(List.of());
        result.setObservation(clip(content));
        return result;
    }

    private Map<String, String> snapshot(ToolCall call) {
        try {
            Path root = workspaceRootResolver.apply(call);
            return root == null ? Map.of() : WorkspaceFingerprint.snapshot(root);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private void applyMetadata(ToolResult result, String toolStatus, String toolErrorCode,
                               boolean risky, WorkspaceFingerprint.DiffResult diff, boolean workspaceChanged) {
        result.setToolStatus(toolStatus);
        result.setToolErrorCode(toolErrorCode);
        result.setRiskLevel(risky ? "high" : "low");
        result.setReadOnly(!risky);
        result.setAffectedPaths(diff.affectedPaths());
        result.setWorkspaceChanged(workspaceChanged);
        result.setDiffSummary(diff.diffSummary());
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
        final int MAX = 4000;
        if (text.length() <= MAX) {
            return text;
        }
        return text.substring(0, MAX) + "\n...[truncated " + (text.length() - MAX) + " chars]";
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
