package cn.lunalhx.ai.domain.agent.service.ledger;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentPlan;
import cn.lunalhx.ai.domain.agent.model.entity.AgentPlanItem;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentPlanItemStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.ReplanReason;
import org.apache.commons.lang3.StringUtils;

import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * Deterministic renderer for system control events appended to the
 * {@link cn.lunalhx.ai.domain.agent.model.entity.ConversationLedger}.
 *
 * <h3>Contract</h3>
 * <p>Every method produces output that is free of runId, elapsedMs, random UUIDs,
 * and temporary paths. The same input always produces the same output.
 *
 * <h3>Scope</h3>
 * <p>Plan snapshots, step-budget snapshots, replan notes, parse-error notes,
 * TODO reminders, user-input markers, and continuation markers.
 */
public final class ControlUpdateTexts {

    private ControlUpdateTexts() {
        // utility class
    }

    // ================================================================
    // Plan snapshot
    // ================================================================

    /**
     * Render a deterministic plan snapshot.
     *
     * <p>Format:
     * <pre>{@code
     * [Plan v{version}]
     * - [{status}] {id}: {content}
     * - [{status}] {id}: {content}
     * }</pre>
     *
     * <p>Evidence and blocker fields are included when present; fields that
     * would change across runs (planId UUID, updatedAt Instant) are omitted.
     */
    public static String renderPlanSnapshot(AgentPlan plan) {
        if (plan == null) {
            return "[Plan] (empty)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[Plan v").append(plan.getVersion()).append("]");
        String items = plan.getItems().stream()
                .sorted(Comparator.comparing(AgentPlanItem::getOrder,
                        Comparator.nullsLast(Integer::compareTo)))
                .map(ControlUpdateTexts::renderPlanItem)
                .collect(Collectors.joining("\n"));
        if (!items.isEmpty()) {
            sb.append('\n').append(items);
        }
        return sb.toString();
    }

    private static String renderPlanItem(AgentPlanItem item) {
        StringBuilder sb = new StringBuilder();
        sb.append("- [").append(item.getStatus() == null
                        ? AgentPlanItemStatus.PENDING.code()
                        : item.getStatus().code())
                .append("] ").append(item.getId())
                .append(": ").append(item.getContent());
        if (StringUtils.isNotBlank(item.getEvidence())) {
            sb.append(" evidence=").append(item.getEvidence());
        }
        if (StringUtils.isNotBlank(item.getBlocker())) {
            sb.append(" blocker=").append(item.getBlocker());
        }
        return sb.toString();
    }

    // ================================================================
    // Step-budget snapshot
    // ================================================================

    /**
     * Render a deterministic step-budget snapshot.
     *
     * <p>Only meaningful when {@code maxSegments > 1}. Format:
     * <pre>{@code
     * [Step Budget] Segment {seg}/{maxSeg}, Step {step}/{maxTotal}, segment limit {limit} steps
     * }</pre>
     *
     * @return the budget text, or empty string when step-budget is not applicable
     */
    public static String renderBudgetSnapshot(AgentContext context) {
        if (context == null || context.getMaxSegments() <= 1) {
            return "";
        }
        return "[Step Budget] Segment " + (context.getSegmentIndex() + 1)
                + "/" + context.getMaxSegments()
                + ", Step " + (context.getStep() + 1)
                + "/" + context.getMaxTotalSteps()
                + ", segment limit " + context.getMaxSteps() + " steps";
    }

    // ================================================================
    // Replan note
    // ================================================================

    /**
     * Render a deterministic replan note.
     *
     * <p>Format:
     * <pre>{@code
     * [Replan] Reason: {reason}, Source: {source}
     * {message}
     * }</pre>
     *
     * <p>The replan message, if present, carries the failure context; it is
     * set by upstream guards before replan starts.
     */
    public static String renderReplanNote(ReplanReason reason, boolean modelUpdated, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Replan] Reason: ").append(reason)
                .append(", Source: ").append(modelUpdated ? "model" : "fallback");
        if (StringUtils.isNotBlank(message)) {
            sb.append('\n').append(message);
        }
        return sb.toString();
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
    public static String renderTodoReminder() {
        return "<reminder>Update your todos with todo_write before continuing.</reminder>";
    }

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
}
