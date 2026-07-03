package cn.lunalhx.ai.domain.agent.service.ledger;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedgerEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of the canonical message list at a point in time.
 *
 * <p>Structure:
 * <pre>{@code
 *   system: stablePrefix.frozenContent()
 *   messages[]: each ConversationLedgerEntry → (role, content)
 * }</pre>
 *
 * <p>Two snapshots with the same generation can be compared message-by-message
 * to determine append-only vs rewritten vs identical.
 */
public final class CanonicalSnapshot {

    private final int generation;
    private final String system;
    private final List<Message> messages;

    public CanonicalSnapshot(int generation, String system, List<Message> messages) {
        this.generation = generation;
        this.system = Objects.requireNonNull(system, "system must not be null");
        this.messages = Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(messages, "messages must not be null")));
    }

    /**
     * Build a canonical snapshot from the agent context.
     *
     * <p>System = stablePrefix.frozenContent(). Messages = ledger entries'
     * role + content, in sequence order.
     */
    public static CanonicalSnapshot from(AgentContext context) {
        Objects.requireNonNull(context, "context must not be null");

        String system = "";
        if (context.getStablePrefix() != null
                && context.getStablePrefix().frozenContent() != null) {
            system = context.getStablePrefix().frozenContent();
        }

        List<Message> msgs = new ArrayList<>();
        if (context.getConversationLedger() != null) {
            for (ConversationLedgerEntry e : context.getConversationLedger().entries()) {
                msgs.add(new Message(e.role(), e.content()));
            }
        }

        return new CanonicalSnapshot(context.getGeneration(), system, msgs);
    }

    public int generation() { return generation; }
    public String system() { return system; }
    public List<Message> messages() { return messages; }
    public int messageCount() { return messages.size(); }

    /**
     * A single message in the canonical list: role + content.
     */
    public record Message(String role, String content) {
        public Message {
            Objects.requireNonNull(role, "role must not be null");
            Objects.requireNonNull(content, "content must not be null");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CanonicalSnapshot that)) return false;
        return generation == that.generation
                && system.equals(that.system)
                && messages.equals(that.messages);
    }

    @Override
    public int hashCode() {
        return Objects.hash(generation, system, messages);
    }

    @Override
    public String toString() {
        return "CanonicalSnapshot{gen=" + generation + ", msgs=" + messages.size() + '}';
    }
}
