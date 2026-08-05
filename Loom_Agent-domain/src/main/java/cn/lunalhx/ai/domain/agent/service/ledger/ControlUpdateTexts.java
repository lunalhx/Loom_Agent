package cn.lunalhx.ai.domain.agent.service.ledger;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import org.apache.commons.lang3.StringUtils;

/**
 * Deterministic renderer for system control events appended to the
 * {@link cn.lunalhx.ai.domain.agent.model.entity.ConversationHistory}.
 *
 * <h3>Contract</h3>
 * <p>Every method produces output that is free of runId, elapsedMs, random UUIDs,
 * and temporary paths. The same input always produces the same output.
 */
public final class ControlUpdateTexts {

    private ControlUpdateTexts() {
        // utility class
    }

    // ================================================================
    // Round budget snapshot
    // ================================================================

    /**
     * Render a deterministic round-budget snapshot appended before each round.
     *
     * <p>Format:
     * <pre>{@code
     * [Round Budget] Tool {toolSteps}/{maxToolSteps}, Attempt {modelAttempts}/{maxAttempts}
     * }</pre>
     *
     * @return the budget text, or empty string when not applicable
     */
    public static String renderRoundBudget(AgentContext context) {
        if (context == null) {
            return "";
        }
        return "[Round Budget] Tool " + context.getToolSteps()
                + "/" + context.getMaxSteps()
                + ", Attempt " + context.getModelAttempts()
                + "/" + context.getMaxAttempts();
    }

    // ================================================================
    // Parse-error note
    // ================================================================

    /**
     * Render a deterministic parse-error note suitable for the ledger.
     *
     * <p>Format:
     * <pre>{@code
     * [Parse Error] {attempt}/{max}
     * {rawModelOutput}
     * }</pre>
     */
    public static String renderParseErrorNote(String modelOutput, int attempt, int maxAttempts) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Parse Error] attempt ").append(attempt)
                .append("/").append(maxAttempts)
                .append("\n模型输出无法解析为 action/final JSON。\nRawOutput:\n");
        sb.append(StringUtils.defaultString(modelOutput));
        return sb.toString();
    }

    // ================================================================
    // TODO reminder
    // ================================================================

    /**
     * The TODO-update reminder text, identical to what
     * {@code ModelPromptFactory} injects as a separate user message.
     */
    

    // ================================================================
    // User input
    // ================================================================

    /**
     * Render a deterministic user-input marker.
     *
     * <p>Format:
     * <pre>{@code
     * [User Input] {message}
     * }</pre>
     */
    public static String renderUserInput(String message) {
        return "[User Input] " + StringUtils.defaultString(message);
    }

    // ================================================================
    // Conversation continuation
    // ================================================================

    /**
     * Render a deterministic continuation marker for a follow-up question
     * in the same conversation.
     *
     * <p>Format:
     * <pre>{@code
     * [Conversation Continued] {question}
     * }</pre>
     */
    public static String renderContinuation(String question) {
        return "[Conversation Continued] " + StringUtils.defaultString(question);
    }

    // ================================================================
    // Config change migration note (C9)
    // ================================================================

    /**
     * Render a deterministic note when the tool/skills configuration fingerprint
     * has changed between runs, causing a generation bump.
     *
     * <p>Format:
     * <pre>{@code
     * [Config Change] Tool/skills configuration has changed. Previous config fingerprint no longer matches current config. New generation {gen} started with inherited stable prefix; old messages remain as-is. Previous fingerprint: {oldFp}. Current fingerprint: {newFp}.
     * }</pre>
     *
     * <p>This is deterministic and free of volatile fields (runId, time, etc.).
     */
    public static String renderConfigChangeNote(String oldFingerprint, String newFingerprint, int newGeneration) {
        return "[Config Change] Tool/skills configuration has changed between runs. "
                + "New generation " + newGeneration
                + " started. Previous config fingerprint: "
                + StringUtils.defaultString(oldFingerprint, "none")
                + ". Current config fingerprint: "
                + StringUtils.defaultString(newFingerprint, "none")
                + ".";
    }

    // ================================================================
    // Approval decision note (C9)
    // ================================================================

    /**
     * Render a deterministic note when an approval is resolved.
     *
     * <p>Format:
     * <pre>{@code
     * [Approval] {decision}: {toolName}{reason}
     * }</pre>
     */
    public static String renderApprovalDecision(String decision, String toolName, String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Approval] ").append(decision)
                .append(": ").append(StringUtils.defaultString(toolName, "unknown"));
        if (StringUtils.isNotBlank(reason)) {
            sb.append(" reason=").append(reason);
        }
        return sb.toString();
    }

    /**
     * Render a note for approval expiration.
     *
     * <p>Format:
     * <pre>{@code
     * [Approval Expired] {approvalId}
     * }</pre>
     */
    public static String renderApprovalExpired(String approvalId) {
        return "[Approval Expired] Approval " + StringUtils.defaultString(approvalId, "unknown")
                + " has expired or is no longer available.";
    }
}
