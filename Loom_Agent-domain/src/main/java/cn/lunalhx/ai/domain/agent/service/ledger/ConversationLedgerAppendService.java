package cn.lunalhx.ai.domain.agent.service.ledger;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedger;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedgerEntry;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.LedgerStableType;

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
 * <h3>Mode gating</h3>
 * <p>A no-op when both {@code enabled} and {@code shadowEnabled} are off.
 *
 * <h3>Immutability</h3>
 * <p>Every append call returns an immutable snapshot of the ledger at that
 * point. Entries are never modified after creation.
 */
public final class ConversationLedgerAppendService {

    private final AgentRuntimeProperties.ConversationLedgerProperties config;

    public ConversationLedgerAppendService(
            AgentRuntimeProperties.ConversationLedgerProperties config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /**
     * Returns {@code true} if at least one ledger mode is active.
     */
    public boolean isActive() {
        return Boolean.TRUE.equals(config.getEnabled())
                || Boolean.TRUE.equals(config.getShadowEnabled());
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
     * @return immutable snapshot of the ledger after append, or empty list if inactive
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
     * @param context   the agent context with an active ledger
     * @param rawOutput the raw tool output (text)
     * @param eventKey  deterministic idempotency key
     * @return immutable snapshot of the ledger after append, or empty list if inactive
     */
    public List<ConversationLedgerEntry> appendToolResult(
            AgentContext context, String rawOutput, String eventKey) {
        Objects.requireNonNull(rawOutput, "rawOutput must not be null");
        String wrapped = "<untrusted_tool_output>\n"
                + rawOutput
                + "\n</untrusted_tool_output>";
        return append(context, "user", wrapped,
                LedgerStableType.TOOL_RESULT, eventKey);
    }

    /**
     * Append a user input message (mid-run).
     *
     * @param context  the agent context with an active ledger
     * @param content  the user input text
     * @param eventKey deterministic idempotency key
     * @return immutable snapshot of the ledger after append, or empty list if inactive
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
     * @return immutable snapshot of the ledger after append, or empty list if inactive
     */
    public List<ConversationLedgerEntry> appendControlUpdate(
            AgentContext context, String content, String eventKey) {
        return append(context, "user", content,
                LedgerStableType.CONTROL_UPDATE, eventKey);
    }

    // ================================================================
    // Internal
    // ================================================================

    private List<ConversationLedgerEntry> append(
            AgentContext context, String role, String content,
            LedgerStableType stableType, String eventKey) {
        Objects.requireNonNull(context, "context must not be null");

        if (!isActive()) {
            return List.of();
        }

        ConversationLedger ledger = context.getConversationLedger();
        if (ledger == null) {
            return List.of();
        }

        ledger.appendWithEventKey(role, content, stableType, eventKey);
        return ledger.entries();
    }
}
