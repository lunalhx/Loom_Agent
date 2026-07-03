package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedger;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedgerEntry;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.agent.model.state.AgentPromptState;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.LedgerStableType;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C1: ConversationLedger / StablePrefix / Generation — unit tests.
 */
public class ConversationLedgerTest {

    // ==================== configuration defaults ====================

    @Test
    public void configDefaultsFalse() {
        AgentRuntimeProperties.ConversationLedgerProperties props =
                new AgentRuntimeProperties.ConversationLedgerProperties();

        assertThat(props.getEnabled()).isFalse();
        assertThat(props.getShadowEnabled()).isFalse();
    }

    @Test
    public void promptStateLedgerInactiveByDefault() {
        AgentPromptState state = new AgentPromptState();

        assertThat(state.isLedgerActive()).isFalse();
        assertThat(state.conversationLedger()).isNull();
        assertThat(state.stablePrefix()).isNull();
        assertThat(state.generation()).isEqualTo(0);
    }

    @Test
    public void contextLedgerInactiveByDefault() {
        AgentContext ctx = new AgentContext();

        assertThat(ctx.isLedgerActive()).isFalse();
        assertThat(ctx.getConversationLedger()).isNull();
        assertThat(ctx.getStablePrefix()).isNull();
        assertThat(ctx.getGeneration()).isEqualTo(0);
    }

    // ==================== ledger append-only ====================

    @Test
    public void appendAndEntries() {
        ConversationLedger ledger = new ConversationLedger();

        ledger.append("user_task", "hello", LedgerStableType.USER_TASK);
        ledger.append("assistant_action", "world", LedgerStableType.ASSISTANT_ACTION);

        List<ConversationLedgerEntry> entries = ledger.entries();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).role()).isEqualTo("user_task");
        assertThat(entries.get(0).content()).isEqualTo("hello");
        assertThat(entries.get(0).stableType()).isEqualTo(LedgerStableType.USER_TASK);
        assertThat(entries.get(0).sequence()).isEqualTo(0);

        assertThat(entries.get(1).role()).isEqualTo("assistant_action");
        assertThat(entries.get(1).content()).isEqualTo("world");
        assertThat(entries.get(1).stableType()).isEqualTo(LedgerStableType.ASSISTANT_ACTION);
        assertThat(entries.get(1).sequence()).isEqualTo(1);

        assertThat(ledger.size()).isEqualTo(2);
        assertThat(ledger.isEmpty()).isFalse();
    }

    @Test
    public void entriesReturnsImmutableView() {
        ConversationLedger ledger = new ConversationLedger();
        ledger.append("user_task", "hello", LedgerStableType.USER_TASK);

        List<ConversationLedgerEntry> snapshot = ledger.entries();
        assertThatThrownBy(() -> snapshot.add(
                ConversationLedgerEntry.builder()
                        .role("test")
                        .content("test")
                        .stableType(LedgerStableType.SYSTEM_NOTE)
                        .build()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    public void appendReturnsThisForFluentChaining() {
        ConversationLedger ledger = new ConversationLedger();
        ConversationLedger result = ledger.append("system_note", "note", LedgerStableType.SYSTEM_NOTE);
        assertThat(result).isSameAs(ledger);
    }

    @Test
    public void entriesSnapshotIndependentOfLaterAppends() {
        ConversationLedger ledger = new ConversationLedger();
        ledger.append("user_task", "first", LedgerStableType.USER_TASK);

        List<ConversationLedgerEntry> firstView = ledger.entries();
        assertThat(firstView).hasSize(1);

        ledger.append("assistant_action", "second", LedgerStableType.ASSISTANT_ACTION);
        assertThat(firstView).hasSize(1); // still 1
        assertThat(ledger.entries()).hasSize(2); // new view has 2
    }

    @Test
    public void sequencesAreMonotonic() {
        ConversationLedger ledger = new ConversationLedger();
        ledger.append("user_task", "a", LedgerStableType.USER_TASK);
        ledger.append("user_task", "b", LedgerStableType.USER_TASK);
        ledger.append("user_task", "c", LedgerStableType.USER_TASK);

        List<ConversationLedgerEntry> entries = ledger.entries();
        assertThat(entries.get(0).sequence()).isEqualTo(0);
        assertThat(entries.get(1).sequence()).isEqualTo(1);
        assertThat(entries.get(2).sequence()).isEqualTo(2);
        assertThat(ledger.nextSequence()).isEqualTo(3);
    }

    @Test
    public void blankRoleThrows() {
        ConversationLedger ledger = new ConversationLedger();
        assertThatThrownBy(() -> ledger.append("  ", "content", LedgerStableType.SYSTEM_NOTE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    public void nullRoleThrows() {
        ConversationLedger ledger = new ConversationLedger();
        assertThatThrownBy(() -> ledger.append(null, "content", LedgerStableType.SYSTEM_NOTE))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    public void emptyContentThrows() {
        ConversationLedger ledger = new ConversationLedger();
        assertThatThrownBy(() -> ledger.append("role", "", LedgerStableType.SYSTEM_NOTE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    public void nullContentThrows() {
        ConversationLedger ledger = new ConversationLedger();
        assertThatThrownBy(() -> ledger.append("role", null, LedgerStableType.SYSTEM_NOTE))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    public void nullStableTypeThrows() {
        ConversationLedger ledger = new ConversationLedger();
        assertThatThrownBy(() -> ledger.append("role", "content", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    public void emptyLedger() {
        ConversationLedger ledger = new ConversationLedger();
        assertThat(ledger.isEmpty()).isTrue();
        assertThat(ledger.size()).isEqualTo(0);
        assertThat(ledger.entries()).isEmpty();
    }

    // ==================== generation / fingerprint state transitions ====================

    @Test
    public void generationStartsAtZero() {
        AgentPromptState state = new AgentPromptState();
        assertThat(state.generation()).isEqualTo(0);
    }

    @Test
    public void incrementGeneration() {
        AgentPromptState state = new AgentPromptState();
        state.ensureLedgerActive();

        assertThat(state.incrementGeneration()).isEqualTo(1);
        assertThat(state.incrementGeneration()).isEqualTo(2);
        assertThat(state.incrementGeneration()).isEqualTo(3);
        assertThat(state.generation()).isEqualTo(3);
    }

    @Test
    public void ensureLedgerActiveCreatesState() {
        AgentPromptState state = new AgentPromptState();
        state.ensureLedgerActive();

        assertThat(state.isLedgerActive()).isTrue();
        assertThat(state.conversationLedger()).isNotNull();
        assertThat(state.generation()).isEqualTo(0);
    }

    @Test
    public void ensureLedgerActiveIsIdempotent() {
        AgentPromptState state = new AgentPromptState();
        state.ensureLedgerActive();
        ConversationLedger first = state.conversationLedger();
        state.incrementGeneration();
        state.incrementGeneration();
        assertThat(state.generation()).isEqualTo(2);

        state.ensureLedgerActive(); // should not reset
        assertThat(state.conversationLedger()).isSameAs(first);
        assertThat(state.generation()).isEqualTo(2);
    }

    @Test
    public void manualSetFields() {
        AgentPromptState state = new AgentPromptState();
        ConversationLedger ledger = new ConversationLedger();
        ledger.append("user_task", "test", LedgerStableType.USER_TASK);
        StablePrefix prefix = new StablePrefix("frozen", "fp1");

        state.setConversationLedger(ledger);
        state.setStablePrefix(prefix);
        state.setGeneration(5);

        assertThat(state.isLedgerActive()).isTrue();
        assertThat(state.conversationLedger()).isSameAs(ledger);
        assertThat(state.stablePrefix()).isSameAs(prefix);
        assertThat(state.generation()).isEqualTo(5);
    }

    @Test
    public void stablePrefixEquality() {
        StablePrefix a = new StablePrefix("content-a", "fp-a");
        StablePrefix b = new StablePrefix("content-a", "fp-a");
        StablePrefix c = new StablePrefix("content-b", "fp-b");

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a.frozenContent()).isEqualTo("content-a");
        assertThat(a.fingerprint()).isEqualTo("fp-a");
    }

    @Test
    public void stablePrefixNullArgs() {
        assertThatThrownBy(() -> new StablePrefix(null, "fp"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StablePrefix("content", null))
                .isInstanceOf(NullPointerException.class);
    }

    // ==================== AgentContext delegates ====================

    @Test
    public void contextEnsureLedgerActiveDelegates() {
        AgentContext ctx = new AgentContext();

        ctx.ensureLedgerActive();
        assertThat(ctx.isLedgerActive()).isTrue();
        assertThat(ctx.getConversationLedger()).isNotNull();
        assertThat(ctx.getGeneration()).isEqualTo(0);

        ctx.incrementGeneration();
        assertThat(ctx.getGeneration()).isEqualTo(1);
    }

    @Test
    public void contextManualSet() {
        AgentContext ctx = new AgentContext();
        ConversationLedger ledger = new ConversationLedger();
        StablePrefix prefix = new StablePrefix("frozen", "fp");

        ctx.setConversationLedger(ledger);
        ctx.setStablePrefix(prefix);
        ctx.setGeneration(42);

        assertThat(ctx.getConversationLedger()).isSameAs(ledger);
        assertThat(ctx.getStablePrefix()).isSameAs(prefix);
        assertThat(ctx.getGeneration()).isEqualTo(42);
    }

    // ==================== ConversationLedgerEntry ====================

    @Test
    public void builderAutoEntryId() {
        ConversationLedgerEntry entry = ConversationLedgerEntry.builder()
                .role("user_task")
                .content("test")
                .stableType(LedgerStableType.USER_TASK)
                .sequence(1L)
                .build();

        assertThat(entry.entryId()).isNotBlank();
        assertThat(entry.role()).isEqualTo("user_task");
        assertThat(entry.content()).isEqualTo("test");
        assertThat(entry.stableType()).isEqualTo(LedgerStableType.USER_TASK);
        assertThat(entry.sequence()).isEqualTo(1);
    }

    @Test
    public void builderNullRoleThrows() {
        assertThatThrownBy(() ->
                ConversationLedgerEntry.builder()
                        .content("test")
                        .stableType(LedgerStableType.SYSTEM_NOTE)
                        .build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    public void builderNullContentThrows() {
        assertThatThrownBy(() ->
                ConversationLedgerEntry.builder()
                        .role("role")
                        .stableType(LedgerStableType.SYSTEM_NOTE)
                        .build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    public void builderNullStableTypeThrows() {
        assertThatThrownBy(() ->
                ConversationLedgerEntry.builder()
                        .role("role")
                        .content("test")
                        .build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    public void entryEqualsAndHashCode() {
        ConversationLedgerEntry e1 = ConversationLedgerEntry.builder()
                .entryId("id-1").sequence(1L).role("r").content("c")
                .stableType(LedgerStableType.USER_TASK).build();
        ConversationLedgerEntry e2 = ConversationLedgerEntry.builder()
                .entryId("id-1").sequence(1L).role("r").content("c")
                .stableType(LedgerStableType.USER_TASK).build();
        ConversationLedgerEntry e3 = ConversationLedgerEntry.builder()
                .entryId("id-2").sequence(1L).role("r").content("c")
                .stableType(LedgerStableType.USER_TASK).build();

        assertThat(e1).isEqualTo(e2);
        assertThat(e1).isNotEqualTo(e3);
        assertThat(e1.hashCode()).isEqualTo(e2.hashCode());
    }

    // ==================== config-driven ledger activation ====================

    @Test
    public void noLedgerStateWhenNotActivated() {
        AgentPromptState state = new AgentPromptState();
        // Do not call ensureLedgerActive
        assertThat(state.isLedgerActive()).isFalse();
        assertThat(state.conversationLedger()).isNull();
        assertThat(state.stablePrefix()).isNull();
    }
}
