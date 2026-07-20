package cn.lunalhx.ai.domain.agent.service.ledger;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedger;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedgerEntry;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.LedgerStableType;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * Unified append service for the {@link ConversationLedger}.
 *
 * <p>Covers four message categories:
 * <ul>
 *   <li><b>Assistant action</b> — model output / reasoning</li>
 *   <li><b>Tool result</b> — tool execution output, mapped to {@code "user"}
 *       role with {@code <untrusted_tool_output>} wrapping</li>
 *   <li><b>User input</b> — mid-run user messages</li>
 *   <li><b>Control update</b> — system control events (approval, undo, etc.),
 *       mapped to {@code "user"} role</li>
 * </ul>
 *
 * <h3>Role mapping</h3>
 * <ul>
 *   <li>{@code ASSISTANT_ACTION} → role {@code "assistant"}</li>
 *   <li>{@code TOOL_RESULT} → role {@code "user"},
 *       content wrapped in {@code <untrusted_tool_output>…</untrusted_tool_output>}</li>
 *   <li>{@code USER_INPUT} → role {@code "user"}</li>
 *   <li>{@code CONTROL_UPDATE} → role {@code "user"}</li>
 * </ul>
 *
 * <h3>Idempotency</h3>
 * <p>Each call accepts an {@code eventKey} derived from run identity, step,
 * and event type. The ledger silently ignores duplicate keys, making
 * checkpoint resume, retry, and node re-entry safe.
 *
 * <h3>Immutability</h3>
 * <p>Every append call returns an immutable snapshot of the ledger at that
 * point. Entries are never modified after creation.
 *
 * <h3>Tool result snip</h3>
 * <p>TOOL_RESULT entries whose raw output exceeds {@code snipThreshold} chars
 * are truncated before being written to the ledger. This prevents a single
 * huge tool result (e.g. 500K-char file read) from escaping both the entry‑count
 * watermark and micro-compaction. The full output is already persisted to blob
 * store by {@code ContextArtifactService} when applicable; the snip protects
 * the ledger window when artifact offloading is disabled or the output falls
 * between the snip threshold and the persistence threshold.
 */
public final class ConversationLedgerAppendService {

    private static final int DEFAULT_SNIP_THRESHOLD = 8000;

    private final AgentRuntimeProperties properties;

    /** No-arg constructor (default threshold). Used by tests. */
    public ConversationLedgerAppendService() {
        this.properties = null;
    }

    /** Constructor with runtime properties for configurable threshold. */
    public ConversationLedgerAppendService(AgentRuntimeProperties properties) {
        this.properties = properties;
    }

    // ================================================================
    // Public append API
    // ================================================================

    /**
     * Append an assistant action (model output).
     *
     * @param context  the agent context with an active ledger
     * @param content  the assistant message content
     * @param eventKey deterministic idempotency key
     * @return immutable snapshot of the ledger after append
     */
    public List<ConversationLedgerEntry> appendAssistant(
            AgentContext context, String content, String eventKey) {
        return append(context, "assistant", content,
                LedgerStableType.ASSISTANT_ACTION, eventKey);
    }

    /**
     * Append a tool result.
     *
     * <p>The raw tool output is wrapped in {@code <untrusted_tool_output>}
     * tags to preserve the injection-protection semantic. Role is {@code "user"}.
     *
     * <p>If {@code rawOutput} exceeds the snip threshold, it is truncated
     * with a {@code [snipped]} marker. See class-level javadoc for rationale.
     *
     * @param context   the agent context with an active ledger
     * @param rawOutput the raw tool output (text)
     * @param eventKey  deterministic idempotency key
     * @return immutable snapshot of the ledger after append
     */
    public List<ConversationLedgerEntry> appendToolResult(
            AgentContext context, String rawOutput, String eventKey) {
        Objects.requireNonNull(rawOutput, "rawOutput must not be null");
        SnipResult snipped = snip(context, rawOutput, null);
        String wrapped = "<untrusted_tool_output>\n"
                + snipped.output
                + "\n</untrusted_tool_output>";
        return appendWithMetadata(context, "user", wrapped,
                LedgerStableType.TOOL_RESULT, eventKey,
                null, null, snipped.originalChars, snipped.renderChars,
                false, 0, false, snipped.didSnip);
    }

    /**
     * Append a tool result with artifact metadata.
     *
     * <p>When a {@link ToolResult} carries an {@code artifactId} (large output
     * persisted to blob store), the metadata is recorded on the ledger entry
     * so that micro-compaction can later replace the full content with a stable
     * {@code <persisted-output>} reference. The prompt content is still the
     * wrapped raw output — metadata does not affect prompt rendering.
     *
     * <p>Snip: if the output was already offloaded to an artifact
     * ({@code toolResult.artifactId} is set), snip is skipped — the content
     * is already a compact reference. Otherwise, output exceeding the threshold
     * is truncated.
     *
     * @param context   the agent context with an active ledger
     * @param rawOutput the raw tool output (text), already rendered for prompt
     * @param toolResult the tool result carrying artifact metadata (may be null)
     * @param toolName  the tool name (may be null)
     * @param eventKey  deterministic idempotency key
     * @return immutable snapshot of the ledger after append
     */
    public List<ConversationLedgerEntry> appendToolResult(
            AgentContext context, String rawOutput, ToolResult toolResult,
            String toolName, String eventKey) {
        Objects.requireNonNull(rawOutput, "rawOutput must not be null");
        boolean alreadyOffloaded = toolResult != null
                && StringUtils.isNotBlank(toolResult.getArtifactId());
        SnipResult snipped = snip(context, rawOutput, alreadyOffloaded ? null : toolResult);
        String wrapped = "<untrusted_tool_output>\n"
                + snipped.output
                + "\n</untrusted_tool_output>";
        String artifactId = toolResult != null ? toolResult.getArtifactId() : null;
        Integer originalChars = toolResult != null ? toolResult.getOriginalChars() : null;
        if (snipped.didSnip && originalChars == null) {
            originalChars = snipped.originalChars;
        }
        return appendWithMetadata(context, "user", wrapped,
                LedgerStableType.TOOL_RESULT, eventKey,
                toolName, artifactId, originalChars, snipped.renderChars,
                false, 0, false, snipped.didSnip);
    }

    /**
     * Append a user input message (mid-run).
     *
     * @param context  the agent context with an active ledger
     * @param content  the user input text
     * @param eventKey deterministic idempotency key
     * @return immutable snapshot of the ledger after append
     */
    public List<ConversationLedgerEntry> appendUserInput(
            AgentContext context, String content, String eventKey) {
        return append(context, "user", content,
                LedgerStableType.USER_INPUT, eventKey);
    }

    /**
     * Append a control update (system event such as approval, undo, etc.).
     *
     * <p>Mapped to {@code "user"} role per the design constraint that only
     * assistant roles produce {@code "assistant"} entries.
     *
     * @param context  the agent context with an active ledger
     * @param content  the control update text
     * @param eventKey deterministic idempotency key
     * @return immutable snapshot of the ledger after append
     */
    public List<ConversationLedgerEntry> appendControlUpdate(
            AgentContext context, String content, String eventKey) {
        return append(context, "user", content,
                LedgerStableType.CONTROL_UPDATE, eventKey);
    }

    /**
     * Append a system note (control / status / compaction marker).
     *
     * <p>Mapped to {@code "user"} role with {@code SYSTEM_NOTE} stable type.
     * Used by progress guards, stop hooks, replan events, format reminders,
     * and compaction summaries.
     *
     * @param context  the agent context with an active ledger
     * @param content  the system note text
     * @param eventKey deterministic idempotency key
     * @return immutable snapshot of the ledger after append
     */
    public List<ConversationLedgerEntry> appendSystemNote(
            AgentContext context, String content, String eventKey) {
        return append(context, "user", content,
                LedgerStableType.SYSTEM_NOTE, eventKey);
    }

    // ================================================================
    // Internal: snip
    // ================================================================

    /**
     * Holds the result of snip processing.
     */
    static final class SnipResult {
        final String output;
        final boolean didSnip;
        final Integer originalChars;
        final Integer renderChars;

        SnipResult(String output, boolean didSnip, Integer originalChars, Integer renderChars) {
            this.output = output;
            this.didSnip = didSnip;
            this.originalChars = originalChars;
            this.renderChars = renderChars;
        }
    }

    /**
     * Truncate raw tool output if it exceeds {@link #snipThreshold}.
     *
     * <p>When {@code toolResult} is non-null and carries an {@code artifactId},
     * the output is assumed to already be an artifact reference (small) — this
     * method is a no-op in that case. Callers should pass {@code null} for
     * {@code toolResult} when skipping this check.
     */
    private SnipResult snip(AgentContext context, String rawOutput, ToolResult toolResult) {
        int snipThreshold = threshold(context);
        int len = StringUtils.length(rawOutput);
        if (len <= snipThreshold) {
            return new SnipResult(rawOutput, false, len, len);
        }

        boolean alreadyOffloaded = toolResult != null
                && StringUtils.isNotBlank(toolResult.getArtifactId());
        if (alreadyOffloaded) {
            return new SnipResult(rawOutput, false,
                    toolResult.getOriginalChars() != null
                            ? toolResult.getOriginalChars() : len, len);
        }

        String artifactRef = toolResult != null && StringUtils.isNotBlank(toolResult.getArtifactId())
                ? "\nFull content: context_recall(action=get, artifactId=" + toolResult.getArtifactId() + ")"
                : "\nFull content may be available via context_recall if this tool output was persisted.";

        String truncated = "[snipped: " + len + " chars total, " + snipThreshold + " shown below]\n"
                + StringUtils.left(rawOutput, snipThreshold)
                + "\n[... remaining " + (len - snipThreshold) + " chars truncated ...]"
                + artifactRef;

        return new SnipResult(truncated, true, len, truncated.length());
    }

    private int threshold(AgentContext context) {
        AgentRuntimeProperties effective = context == null || properties == null
                ? properties : context.runtimeProperties(properties);
        return effective != null && effective.getObservationMaxChars() != null
                && effective.getObservationMaxChars() > 0
                ? effective.getObservationMaxChars() : DEFAULT_SNIP_THRESHOLD;
    }

    // ================================================================
    // Internal: append
    // ================================================================

    private List<ConversationLedgerEntry> append(
            AgentContext context, String role, String content,
            LedgerStableType stableType, String eventKey) {
        return appendWithMetadata(context, role, content, stableType, eventKey,
                null, null, null, null, false, 0, false, false);
    }

    private List<ConversationLedgerEntry> appendWithMetadata(
            AgentContext context, String role, String content,
            LedgerStableType stableType, String eventKey,
            String toolName, String artifactId,
            Integer originalChars, Integer renderChars,
            boolean compacted, int compactionDepth,
            boolean microCompacted, boolean snipped) {
        Objects.requireNonNull(context, "context must not be null");

        context.ensureLedgerActive();
        ConversationLedger ledger = context.getConversationLedger();

        ledger.appendWithEventKey(role, content, stableType, eventKey,
                toolName, artifactId, originalChars, renderChars,
                compacted, compactionDepth, microCompacted, snipped);
        return ledger.entries();
    }
}
