package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedger;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedgerEntry;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.LedgerStableType;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerInitializer;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * C4: ConversationLedger initializer, append service, idempotency, v2 migration,
 * immutability, and mode gating tests.
 */
public class ConversationLedgerC4Test {

    private AgentRuntimeProperties.ConversationLedgerProperties enabledConfig;
    private AgentRuntimeProperties.ConversationLedgerProperties shadowConfig;
    private AgentRuntimeProperties.ConversationLedgerProperties disabledConfig;
    private StablePrefix stablePrefix;

    @Before
    public void setUp() {
        enabledConfig = new AgentRuntimeProperties.ConversationLedgerProperties();
        enabledConfig.setEnabled(true);
        enabledConfig.setShadowEnabled(false);

        shadowConfig = new AgentRuntimeProperties.ConversationLedgerProperties();
        shadowConfig.setEnabled(false);
        shadowConfig.setShadowEnabled(true);

        disabledConfig = new AgentRuntimeProperties.ConversationLedgerProperties();
        disabledConfig.setEnabled(false);
        disabledConfig.setShadowEnabled(false);

        stablePrefix = new StablePrefix("frozen-content-for-tests",
                "sha256-" + System.currentTimeMillis());
    }

    private AgentContext newContext(String runId, String question) {
        AgentContext ctx = new AgentContext();
        ctx.setRunId(runId);
        ctx.setQuestion(question);
        return ctx;
    }

    // ================================================================
    // 1. New run initialization — idempotent
    // ================================================================

    @Test
    public void newConversationInitFreezesStablePrefix() {
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(enabledConfig);
        AgentContext ctx = newContext("r-init-1", "hello world");

        init.initializeNewConversation(ctx, stablePrefix);

        assertNotNull("stable prefix must be set", ctx.getStablePrefix());
        assertEquals(stablePrefix, ctx.getStablePrefix());
        assertEquals(0, ctx.getGeneration());
    }

    @Test
    public void newConversationInitCreatesLedgerWithUserTask() {
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(enabledConfig);
        AgentContext ctx = newContext("r-init-2", "do something");

        init.initializeNewConversation(ctx, stablePrefix);

        ConversationLedger ledger = ctx.getConversationLedger();
        assertNotNull("ledger must be created", ledger);
        assertEquals(1, ledger.size());
        assertEquals(1, ledger.nextSequence());

        ConversationLedgerEntry entry = ledger.entries().get(0);
        assertEquals("user", entry.role());
        assertEquals("do something", entry.content());
        assertEquals(LedgerStableType.USER_TASK, entry.stableType());
        assertEquals(0, entry.sequence());
        assertNotNull("event key must be set", entry.eventKey());
        assertThat(entry.eventKey()).contains("r-init-2:init:user_task");
    }

    @Test
    public void newConversationInitIsIdempotent() {
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(enabledConfig);
        AgentContext ctx = newContext("r-init-3", "task");

        init.initializeNewConversation(ctx, stablePrefix);
        assertEquals(1, ctx.getConversationLedger().size());
        assertEquals(0, ctx.getGeneration());

        // Call again — must be idempotent (same event keys)
        init.initializeNewConversation(ctx, stablePrefix);
        assertEquals("init must be idempotent", 1, ctx.getConversationLedger().size());
        assertEquals("generation must not change", 0, ctx.getGeneration());
    }

    @Test
    public void newConversationInitWithNullQuestion() {
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(enabledConfig);
        AgentContext ctx = newContext("r-init-4", null);

        init.initializeNewConversation(ctx, stablePrefix);

        // Ledger must exist but be empty (no question to append)
        ConversationLedger ledger = ctx.getConversationLedger();
        assertNotNull(ledger);
        assertTrue(ledger.isEmpty());
        assertEquals(stablePrefix, ctx.getStablePrefix());
        assertEquals(0, ctx.getGeneration());
    }

    @Test
    public void newConversationInitWithEmptyQuestion() {
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(enabledConfig);
        AgentContext ctx = newContext("r-init-5", "");

        init.initializeNewConversation(ctx, stablePrefix);

        ConversationLedger ledger = ctx.getConversationLedger();
        assertNotNull(ledger);
        assertTrue(ledger.isEmpty());
    }

    // ================================================================
    // 2. Node re-entry does not duplicate
    // ================================================================

    @Test
    public void appendAssistantDedupByEventKey() {
        ConversationLedgerAppendService svc = new ConversationLedgerAppendService(enabledConfig);
        AgentContext ctx = newContext("r-dedup-1", "task");
        ctx.ensureLedgerActive();

        String eventKey = "r-dedup-1:5:assistant";
        List<ConversationLedgerEntry> s1 = svc.appendAssistant(ctx, "thinking 1", eventKey);
        assertEquals(1, s1.size());

        // Same event key — must be no-op
        List<ConversationLedgerEntry> s2 = svc.appendAssistant(ctx, "thinking 2", eventKey);
        assertEquals("duplicate event key must be ignored", 1, s2.size());
        assertEquals("content must be from first append", "thinking 1",
                s2.get(0).content());
        assertEquals(1, ctx.getConversationLedger().size());
    }

    @Test
    public void appendToolResultDedupByEventKey() {
        ConversationLedgerAppendService svc = new ConversationLedgerAppendService(enabledConfig);
        AgentContext ctx = newContext("r-dedup-2", "task");
        ctx.ensureLedgerActive();

        String eventKey = "r-dedup-2:3:tool_result";
        svc.appendToolResult(ctx, "output v1", eventKey);
        svc.appendToolResult(ctx, "output v2", eventKey);

        assertEquals(1, ctx.getConversationLedger().size());
        ConversationLedgerEntry entry = ctx.getConversationLedger().entries().get(0);
        assertThat(entry.content()).contains("output v1");
        assertThat(entry.content()).contains("<untrusted_tool_output>");
        assertThat(entry.eventKey()).isEqualTo(eventKey);
    }

    @Test
    public void differentEventKeysProduceDifferentEntries() {
        ConversationLedgerAppendService svc = new ConversationLedgerAppendService(enabledConfig);
        AgentContext ctx = newContext("r-dedup-3", "task");
        ctx.ensureLedgerActive();

        svc.appendAssistant(ctx, "step-1", "r-dedup-3:1:assistant");
        svc.appendAssistant(ctx, "step-2", "r-dedup-3:2:assistant");
        svc.appendAssistant(ctx, "step-3", "r-dedup-3:3:assistant");

        assertEquals(3, ctx.getConversationLedger().size());
        List<ConversationLedgerEntry> entries = ctx.getConversationLedger().entries();
        assertEquals("step-1", entries.get(0).content());
        assertEquals("step-2", entries.get(1).content());
        assertEquals("step-3", entries.get(2).content());
    }

    @Test
    public void nullEventKeyBypassesDedup() {
        // Backward compat: null eventKey does not dedup
        ConversationLedger ledger = new ConversationLedger();
        ledger.appendWithEventKey("user", "a", LedgerStableType.SYSTEM_NOTE, null);
        ledger.appendWithEventKey("user", "a", LedgerStableType.SYSTEM_NOTE, null);
        assertEquals(2, ledger.size());
    }

    // ================================================================
    // 3. Different events append correctly
    // ================================================================

    @Test
    public void appendAssistantUsesAssistantRole() {
        ConversationLedgerAppendService svc = new ConversationLedgerAppendService(enabledConfig);
        AgentContext ctx = newContext("r-types-1", "task");
        ctx.ensureLedgerActive();

        svc.appendAssistant(ctx, "model output", "r-types-1:1:assistant");
        ConversationLedgerEntry entry = ctx.getConversationLedger().entries().get(0);
        assertEquals("assistant", entry.role());
        assertEquals(LedgerStableType.ASSISTANT_ACTION, entry.stableType());
        assertEquals("model output", entry.content());
    }

    @Test
    public void appendToolResultUsesUserRoleAndWrapsUntrusted() {
        ConversationLedgerAppendService svc = new ConversationLedgerAppendService(enabledConfig);
        AgentContext ctx = newContext("r-types-2", "task");
        ctx.ensureLedgerActive();

        svc.appendToolResult(ctx, "raw tool output\nline2", "r-types-2:1:tool_result");
        ConversationLedgerEntry entry = ctx.getConversationLedger().entries().get(0);
        assertEquals("user", entry.role());
        assertEquals(LedgerStableType.TOOL_RESULT, entry.stableType());

        String content = entry.content();
        assertThat(content).startsWith("<untrusted_tool_output>");
        assertThat(content).endsWith("</untrusted_tool_output>");
        assertThat(content).contains("raw tool output");
        assertThat(content).contains("line2");
    }

    @Test
    public void appendUserInputUsesUserRole() {
        ConversationLedgerAppendService svc = new ConversationLedgerAppendService(enabledConfig);
        AgentContext ctx = newContext("r-types-3", "task");
        ctx.ensureLedgerActive();

        svc.appendUserInput(ctx, "user says hi", "r-types-3:1:user_input");
        ConversationLedgerEntry entry = ctx.getConversationLedger().entries().get(0);
        assertEquals("user", entry.role());
        assertEquals(LedgerStableType.USER_INPUT, entry.stableType());
        assertEquals("user says hi", entry.content());
    }

    @Test
    public void appendControlUpdateUsesUserRole() {
        ConversationLedgerAppendService svc = new ConversationLedgerAppendService(enabledConfig);
        AgentContext ctx = newContext("r-types-4", "task");
        ctx.ensureLedgerActive();

        svc.appendControlUpdate(ctx, "approval granted", "r-types-4:1:control_update");
        ConversationLedgerEntry entry = ctx.getConversationLedger().entries().get(0);
        assertEquals("user", entry.role());
        assertEquals(LedgerStableType.CONTROL_UPDATE, entry.stableType());
        assertEquals("approval granted", entry.content());
    }

    @Test
    public void allFourTypesAppendInSequence() {
        ConversationLedgerAppendService svc = new ConversationLedgerAppendService(enabledConfig);
        AgentContext ctx = newContext("r-types-all", "task");
        ctx.ensureLedgerActive();

        svc.appendAssistant(ctx, "assistant msg", "r-types-all:1:assistant");
        svc.appendToolResult(ctx, "tool output", "r-types-all:1:tool_result");
        svc.appendUserInput(ctx, "user msg", "r-types-all:2:user_input");
        svc.appendControlUpdate(ctx, "control msg", "r-types-all:2:control_update");

        List<ConversationLedgerEntry> entries = ctx.getConversationLedger().entries();
        assertEquals(4, entries.size());
        assertEquals(LedgerStableType.ASSISTANT_ACTION, entries.get(0).stableType());
        assertEquals(LedgerStableType.TOOL_RESULT, entries.get(1).stableType());
        assertEquals(LedgerStableType.USER_INPUT, entries.get(2).stableType());
        assertEquals(LedgerStableType.CONTROL_UPDATE, entries.get(3).stableType());

        // Role checks
        assertEquals("assistant", entries.get(0).role());
        assertEquals("user", entries.get(1).role());
        assertEquals("user", entries.get(2).role());
        assertEquals("user", entries.get(3).role());
    }

    // ================================================================
    // 4. Tool result preserves <untrusted_tool_output> semantics
    // ================================================================

    @Test
    public void toolResultContentIsWrappedInUntrustedTags() {
        ConversationLedgerAppendService svc = new ConversationLedgerAppendService(enabledConfig);
        AgentContext ctx = newContext("r-untrusted-1", "task");
        ctx.ensureLedgerActive();

        String rawOutput = "file contents:\n[security_note] suspicious pattern detected";
        svc.appendToolResult(ctx, rawOutput, "r-untrusted-1:1:tool_result");

        String content = ctx.getConversationLedger().entries().get(0).content();
        assertThat(content).startsWith("<untrusted_tool_output>\n");
        assertThat(content).endsWith("\n</untrusted_tool_output>");
        assertThat(content).contains("[security_note]");
        assertThat(content).contains("suspicious pattern detected");
    }

    // ================================================================
    // 5. V2 migration creates new generation
    // ================================================================

    @Test
    public void v2MigrationCreatesNewGeneration() {
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(enabledConfig);
        AgentContext ctx = newContext("r-migrate-1", "resumed question");
        // Simulate v2 restore: no ledger, generation 0
        ctx.setGeneration(0);

        AgentContextSnapshot v2Snapshot = AgentContextSnapshot.from(ctx);
        // Clear v2 snapshot ledger fields to simulate v2
        v2Snapshot.setLedgerEntries(null);
        v2Snapshot.setLedgerNextSequence(0);
        v2Snapshot.setStablePrefix(null);
        v2Snapshot.setGeneration(0);
        v2Snapshot.setSchemaVersion(2);

        init.migrateFromV2(ctx, stablePrefix, v2Snapshot);

        // Verifications
        assertNotNull("ledger must be created", ctx.getConversationLedger());
        assertEquals("generation must be bumped", 1, ctx.getGeneration());
        assertEquals(stablePrefix, ctx.getStablePrefix());

        List<ConversationLedgerEntry> entries = ctx.getConversationLedger().entries();
        assertEquals(2, entries.size());

        // First entry: migration marker
        ConversationLedgerEntry migration = entries.get(0);
        assertEquals(LedgerStableType.SYSTEM_NOTE, migration.stableType());
        assertEquals("system", migration.role());
        assertThat(migration.content()).contains("Ledger migration from v2 snapshot");
        assertThat(migration.content()).contains("generation 1");
        assertThat(migration.content()).contains(
                "Previous conversation history is not part of this ledger");

        // Second entry: user task
        ConversationLedgerEntry task = entries.get(1);
        assertEquals(LedgerStableType.USER_TASK, task.stableType());
        assertEquals("user", task.role());
        assertEquals("resumed question", task.content());
    }

    @Test
    public void v2MigrationIsIdempotent() {
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(enabledConfig);
        AgentContext ctx = newContext("r-migrate-2", "resumed question");
        ctx.setGeneration(0);

        AgentContextSnapshot v2Snapshot = AgentContextSnapshot.from(ctx);
        v2Snapshot.setLedgerEntries(null);
        v2Snapshot.setLedgerNextSequence(0);
        v2Snapshot.setStablePrefix(null);
        v2Snapshot.setGeneration(0);
        v2Snapshot.setSchemaVersion(2);

        init.migrateFromV2(ctx, stablePrefix, v2Snapshot);
        assertEquals(2, ctx.getConversationLedger().size());
        assertEquals(1, ctx.getGeneration());

        // Call again — idempotent via same event keys
        init.migrateFromV2(ctx, stablePrefix, v2Snapshot);
        assertEquals("migration must be idempotent", 2, ctx.getConversationLedger().size());
        assertEquals("generation must not change on re-call", 1, ctx.getGeneration());
    }

    @Test
    public void v2MigrationDoesNotClaimOldHistoryIsAppendOnly() {
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(enabledConfig);
        AgentContext ctx = newContext("r-migrate-3", "resumed");
        ctx.setGeneration(0);

        AgentContextSnapshot v2Snapshot = AgentContextSnapshot.from(ctx);
        v2Snapshot.setLedgerEntries(null);
        v2Snapshot.setLedgerNextSequence(0);
        v2Snapshot.setStablePrefix(null);
        v2Snapshot.setGeneration(0);
        v2Snapshot.setSchemaVersion(2);

        init.migrateFromV2(ctx, stablePrefix, v2Snapshot);

        // The migration marker must explicitly state that old history is NOT in the ledger
        String firstEntry = ctx.getConversationLedger().entries().get(0).content();
        assertThat(firstEntry).contains("not part of this ledger");
        assertThat(firstEntry).doesNotContain("migrated successfully");
        assertThat(firstEntry).doesNotContain("imported");
    }

    @Test
    public void v2MigrationWithoutQuestion() {
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(enabledConfig);
        AgentContext ctx = newContext("r-migrate-4", null);
        ctx.setGeneration(0);

        AgentContextSnapshot v2Snapshot = AgentContextSnapshot.from(ctx);
        v2Snapshot.setLedgerEntries(null);
        v2Snapshot.setLedgerNextSequence(0);
        v2Snapshot.setStablePrefix(null);
        v2Snapshot.setGeneration(0);
        v2Snapshot.setSchemaVersion(2);

        init.migrateFromV2(ctx, stablePrefix, v2Snapshot);

        // Only migration marker, no user task
        assertEquals(1, ctx.getConversationLedger().size());
        assertEquals(LedgerStableType.SYSTEM_NOTE,
                ctx.getConversationLedger().entries().get(0).stableType());
    }

    @Test
    public void v2MigrationBumpsGenerationFromExistingValue() {
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(enabledConfig);
        AgentContext ctx = newContext("r-migrate-5", "question");
        ctx.setGeneration(5);

        AgentContextSnapshot v2Snapshot = AgentContextSnapshot.from(ctx);
        v2Snapshot.setLedgerEntries(null);
        v2Snapshot.setLedgerNextSequence(0);
        v2Snapshot.setStablePrefix(null);
        v2Snapshot.setGeneration(5);
        v2Snapshot.setSchemaVersion(2);

        init.migrateFromV2(ctx, stablePrefix, v2Snapshot);

        assertEquals("generation must increment from 5 to 6", 6, ctx.getGeneration());
        assertThat(ctx.getConversationLedger().entries().get(0).content())
                .contains("generation 6");
    }

    // ================================================================
    // 6. Old entries are immutable
    // ================================================================

    @Test
    public void ledgerEntriesReturnsImmutableList() {
        ConversationLedgerAppendService svc = new ConversationLedgerAppendService(enabledConfig);
        AgentContext ctx = newContext("r-immut-1", "task");
        ctx.ensureLedgerActive();

        svc.appendAssistant(ctx, "msg", "r-immut-1:1:assistant");
        List<ConversationLedgerEntry> snapshot = svc.appendAssistant(
                ctx, "msg2", "r-immut-1:2:assistant");

        // snapshot is List.copyOf (immutable)
        try {
            snapshot.add(ConversationLedgerEntry.builder()
                    .role("r").content("c").stableType(LedgerStableType.SYSTEM_NOTE).build());
            throw new AssertionError("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    public void entriesCannotBeMutatedAfterCreation() {
        ConversationLedger ledger = new ConversationLedger();
        ledger.appendWithEventKey("user", "original",
                LedgerStableType.USER_TASK, "key-1");

        List<ConversationLedgerEntry> view1 = ledger.entries();
        assertEquals(1, view1.size());

        // Append more — view1 unchanged (snapshot independence)
        ledger.appendWithEventKey("assistant", "new",
                LedgerStableType.ASSISTANT_ACTION, "key-2");
        assertEquals(1, view1.size());
        assertEquals(2, ledger.entries().size());
    }

    @Test
    public void entryContentCannotBeModifiedAfterCreation() {
        ConversationLedgerEntry entry = ConversationLedgerEntry.builder()
                .role("user")
                .content("original content")
                .stableType(LedgerStableType.USER_TASK)
                .sequence(1L)
                .eventKey("key-1")
                .build();

        // entryId must exist
        assertNotNull(entry.entryId());
        // All fields match construction
        assertEquals("original content", entry.content());
        assertEquals("user", entry.role());
        assertEquals(LedgerStableType.USER_TASK, entry.stableType());
        assertEquals(1L, entry.sequence());
        assertEquals("key-1", entry.eventKey());
    }

    // ================================================================
    // 7. Mode gating: both off → no state
    // ================================================================

    @Test
    public void disabledModeProducesNoLedgerOnInit() {
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(disabledConfig);
        AgentContext ctx = newContext("r-off-1", "task");

        init.initializeNewConversation(ctx, stablePrefix);
        assertNull(ctx.getConversationLedger());
        assertNull(ctx.getStablePrefix());
        assertEquals(0, ctx.getGeneration());
    }

    @Test
    public void disabledModeProducesNoMigrationState() {
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(disabledConfig);
        AgentContext ctx = newContext("r-off-2", "question");
        AgentContextSnapshot v2Snapshot = AgentContextSnapshot.from(ctx);
        v2Snapshot.setLedgerEntries(null);
        v2Snapshot.setLedgerNextSequence(0);
        v2Snapshot.setSchemaVersion(2);

        init.migrateFromV2(ctx, stablePrefix, v2Snapshot);

        assertNull(ctx.getConversationLedger());
        assertNull(ctx.getStablePrefix());
        assertEquals(0, ctx.getGeneration());
    }

    @Test
    public void disabledAppendServiceReturnsEmptyList() {
        ConversationLedgerAppendService svc = new ConversationLedgerAppendService(disabledConfig);
        AgentContext ctx = newContext("r-off-3", "task");
        ctx.ensureLedgerActive();

        List<ConversationLedgerEntry> result = svc.appendAssistant(
                ctx, "msg", "r-off-3:1:assistant");
        assertTrue(result.isEmpty());
        // Ledger in context is NOT mutated
        assertEquals(0, ctx.getConversationLedger().size());
    }

    @Test
    public void disabledAppendServiceWithNullLedgerDoesNotNPE() {
        ConversationLedgerAppendService svc = new ConversationLedgerAppendService(disabledConfig);
        AgentContext ctx = newContext("r-off-4", "task");
        // ledger not activated

        List<ConversationLedgerEntry> result = svc.appendAssistant(
                ctx, "msg", "r-off-4:1:assistant");
        assertTrue(result.isEmpty());
        assertNull(ctx.getConversationLedger());
    }

    // ================================================================
    // 8. Shadow mode still initializes but append service checks mode
    // ================================================================

    @Test
    public void shadowModeInitCreatesLedgerState() {
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(shadowConfig);
        AgentContext ctx = newContext("r-shadow-1", "task in shadow mode");

        init.initializeNewConversation(ctx, stablePrefix);

        assertNotNull("shadow mode must create ledger", ctx.getConversationLedger());
        assertNotNull("shadow mode must set stable prefix", ctx.getStablePrefix());
        assertEquals(1, ctx.getConversationLedger().size());
    }

    @Test
    public void shadowModeAppendServiceIsActive() {
        ConversationLedgerAppendService svc = new ConversationLedgerAppendService(shadowConfig);
        assertTrue("shadow mode must be active", svc.isActive());
    }

    @Test
    public void isActiveCorrectForEachConfig() {
        assertTrue(new ConversationLedgerInitializer(enabledConfig).isActive());
        assertTrue(new ConversationLedgerInitializer(shadowConfig).isActive());
        assertFalse(new ConversationLedgerInitializer(disabledConfig).isActive());

        assertTrue(new ConversationLedgerAppendService(enabledConfig).isActive());
        assertTrue(new ConversationLedgerAppendService(shadowConfig).isActive());
        assertFalse(new ConversationLedgerAppendService(disabledConfig).isActive());
    }

    // ================================================================
    // 9. Event key format and fromPersisted rebuild
    // ================================================================

    @Test
    public void eventKeyFormatAdheresToConvention() {
        String key = ConversationLedgerInitializer.eventKey("run-123", "5", "assistant");
        assertEquals("run-123:5:assistant", key);
    }

    @Test
    public void eventKeyWithInitPhase() {
        String key = ConversationLedgerInitializer.eventKey("run-abc", "init", "user_task");
        assertEquals("run-abc:init:user_task", key);
    }

    @Test
    public void eventKeyWithMigratePhase() {
        String key = ConversationLedgerInitializer.eventKey("run-xyz", "migrate", "generation_2");
        assertEquals("run-xyz:migrate:generation_2", key);
    }

    @Test
    public void fromPersistedRebuildsSeenEventKeys() {
        ConversationLedger original = new ConversationLedger();
        original.appendWithEventKey("user", "a", LedgerStableType.USER_TASK, "key-a");
        original.appendWithEventKey("assistant", "b", LedgerStableType.ASSISTANT_ACTION, "key-b");
        original.appendWithEventKey("user", "c", LedgerStableType.TOOL_RESULT, "key-c");

        List<ConversationLedgerEntry> persisted = original.entries();
        long nextSeq = original.nextSequence();

        ConversationLedger rebuilt = ConversationLedger.fromPersisted(persisted, nextSeq);
        assertEquals(3, rebuilt.size());
        assertEquals(3, rebuilt.nextSequence());

        // Append with same event keys — must be deduped
        rebuilt.appendWithEventKey("user", "a-new", LedgerStableType.USER_TASK, "key-a");
        rebuilt.appendWithEventKey("assistant", "b-new", LedgerStableType.ASSISTANT_ACTION, "key-b");
        rebuilt.appendWithEventKey("user", "c-new", LedgerStableType.TOOL_RESULT, "key-c");
        assertEquals("all three must be deduped", 3, rebuilt.size());
    }

    @Test
    public void fromPersistedWithNullEventKeysDoesNotCrash() {
        ConversationLedger original = new ConversationLedger();
        original.append("user", "no-key", LedgerStableType.SYSTEM_NOTE); // no event key
        original.append("user", "also-no-key", LedgerStableType.SYSTEM_NOTE);

        List<ConversationLedgerEntry> persisted = original.entries();
        ConversationLedger rebuilt = ConversationLedger.fromPersisted(
                persisted, original.nextSequence());
        assertEquals(2, rebuilt.size());

        // Adding with the same null keys still appends (no dedup)
        rebuilt.append("user", "third", LedgerStableType.SYSTEM_NOTE);
        assertEquals(3, rebuilt.size());
    }

    @Test
    public void fromPersistedWithNullList() {
        ConversationLedger ledger = ConversationLedger.fromPersisted(null, 0);
        assertTrue(ledger.isEmpty());
        assertEquals(0, ledger.nextSequence());
    }

    // ================================================================
    // 10. Append service returns immutable snapshot
    // ================================================================

    @Test
    public void appendServiceReturnIsImmutable() {
        ConversationLedgerAppendService svc = new ConversationLedgerAppendService(enabledConfig);
        AgentContext ctx = newContext("r-snapshot-1", "task");
        ctx.ensureLedgerActive();

        List<ConversationLedgerEntry> snapshot = svc.appendAssistant(
                ctx, "msg", "r-snapshot-1:1:assistant");

        assertNotNull(snapshot);
        assertEquals(1, snapshot.size());

        try {
            snapshot.add(ConversationLedgerEntry.builder()
                    .role("r").content("c").stableType(LedgerStableType.SYSTEM_NOTE).build());
            throw new AssertionError("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // expected — snapshot is immutable
        }
    }

    @Test
    public void appendServiceSnapshotIndependentOfLaterAppends() {
        ConversationLedgerAppendService svc = new ConversationLedgerAppendService(enabledConfig);
        AgentContext ctx = newContext("r-snapshot-2", "task");
        ctx.ensureLedgerActive();

        List<ConversationLedgerEntry> s1 = svc.appendAssistant(
                ctx, "msg1", "r-snapshot-2:1:assistant");
        assertEquals(1, s1.size());

        List<ConversationLedgerEntry> s2 = svc.appendAssistant(
                ctx, "msg2", "r-snapshot-2:2:assistant");
        assertEquals(2, s2.size());
        assertEquals(1, s1.size()); // s1 unchanged
    }

    // ================================================================
    // 11. CONTROL_UPDATE stable type exists
    // ================================================================

    @Test
    public void controlUpdateStableTypeIsAvailable() {
        LedgerStableType type = LedgerStableType.valueOf("CONTROL_UPDATE");
        assertEquals("control_update", type.code());
    }

    @Test
    public void allSixStableTypesAreDistinct() {
        LedgerStableType[] types = LedgerStableType.values();
        assertEquals(6, types.length);
        // All codes are distinct
        long distinctCodes = java.util.Arrays.stream(types)
                .map(LedgerStableType::code)
                .distinct()
                .count();
        assertEquals(6, distinctCodes);
    }

    // ================================================================
    // 12. Checkpoint resume idempotency
    // ================================================================

    @Test
    public void checkpointResumeDoesNotDuplicateEntries() {
        ConversationLedgerAppendService svc = new ConversationLedgerAppendService(enabledConfig);
        AgentContext ctx = newContext("r-checkpoint-1", "task");
        ctx.ensureLedgerActive();

        // Simulate step 1, 2, 3
        svc.appendAssistant(ctx, "step-1", "r-checkpoint-1:1:assistant");
        svc.appendToolResult(ctx, "result-1", "r-checkpoint-1:1:tool_result");
        svc.appendAssistant(ctx, "step-2", "r-checkpoint-1:2:assistant");
        svc.appendToolResult(ctx, "result-2", "r-checkpoint-1:2:tool_result");

        assertEquals(4, ctx.getConversationLedger().size());

        // Simulate checkpoint resume: replay step 1 and 2 events
        svc.appendAssistant(ctx, "step-1", "r-checkpoint-1:1:assistant");
        svc.appendToolResult(ctx, "result-1", "r-checkpoint-1:1:tool_result");
        svc.appendAssistant(ctx, "step-2", "r-checkpoint-1:2:assistant");
        svc.appendToolResult(ctx, "result-2", "r-checkpoint-1:2:tool_result");

        assertEquals("checkpoint resume must not duplicate entries", 4,
                ctx.getConversationLedger().size());
    }

    // ================================================================
    // 13. Sequences are monotonic across all append types
    // ================================================================

    @Test
    public void sequencesAreMonotonicAcrossAllTypes() {
        ConversationLedgerAppendService svc = new ConversationLedgerAppendService(enabledConfig);
        AgentContext ctx = newContext("r-seq-1", "task");
        ctx.ensureLedgerActive();

        svc.appendAssistant(ctx, "a", "r-seq-1:1:assistant");
        svc.appendToolResult(ctx, "b", "r-seq-1:1:tool_result");
        svc.appendUserInput(ctx, "c", "r-seq-1:2:user_input");
        svc.appendControlUpdate(ctx, "d", "r-seq-1:2:control_update");
        svc.appendAssistant(ctx, "e", "r-seq-1:3:assistant");

        List<ConversationLedgerEntry> entries = ctx.getConversationLedger().entries();
        assertEquals(5, entries.size());
        for (int i = 0; i < entries.size(); i++) {
            assertEquals("sequence must be " + i, i, entries.get(i).sequence());
        }
        assertEquals(5, ctx.getConversationLedger().nextSequence());
    }

    // ================================================================
    // 14. Null context / null content handling
    // ================================================================

    @Test(expected = NullPointerException.class)
    public void appendServiceNullContextThrows() {
        ConversationLedgerAppendService svc = new ConversationLedgerAppendService(enabledConfig);
        svc.appendAssistant(null, "msg", "key");
    }

    @Test(expected = NullPointerException.class)
    public void appendToolResultNullContentThrows() {
        ConversationLedgerAppendService svc = new ConversationLedgerAppendService(enabledConfig);
        AgentContext ctx = newContext("r-null-1", "task");
        ctx.ensureLedgerActive();
        svc.appendToolResult(ctx, null, "key");
    }

    @Test(expected = NullPointerException.class)
    public void initializerNullContextThrows() {
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(enabledConfig);
        init.initializeNewConversation(null, stablePrefix);
    }

    @Test(expected = NullPointerException.class)
    public void initializerNullStablePrefixThrows() {
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(enabledConfig);
        init.initializeNewConversation(newContext("r", "q"), null);
    }

    @Test(expected = NullPointerException.class)
    public void migrateNullV2SnapshotThrows() {
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(enabledConfig);
        init.migrateFromV2(newContext("r", "q"), stablePrefix, null);
    }

    // ================================================================
    // 15. ConversationLedgerEntry eventKey in equals/hashCode
    // ================================================================

    @Test
    public void entriesWithDifferentEventKeysAreNotEqual() {
        ConversationLedgerEntry e1 = ConversationLedgerEntry.builder()
                .entryId("id-1").sequence(1L).role("user").content("c")
                .stableType(LedgerStableType.USER_TASK).eventKey("key-a").build();
        ConversationLedgerEntry e2 = ConversationLedgerEntry.builder()
                .entryId("id-1").sequence(1L).role("user").content("c")
                .stableType(LedgerStableType.USER_TASK).eventKey("key-b").build();

        assertNotEquals("entries with different eventKeys must not be equal", e1, e2);
        assertNotEquals("hashCodes must differ", e1.hashCode(), e2.hashCode());
    }

    @Test
    public void entriesWithSameEventKeyAreEqual() {
        ConversationLedgerEntry e1 = ConversationLedgerEntry.builder()
                .entryId("id-1").sequence(1L).role("user").content("c")
                .stableType(LedgerStableType.USER_TASK).eventKey("key-a").build();
        ConversationLedgerEntry e2 = ConversationLedgerEntry.builder()
                .entryId("id-1").sequence(1L).role("user").content("c")
                .stableType(LedgerStableType.USER_TASK).eventKey("key-a").build();

        assertEquals(e1, e2);
        assertEquals(e1.hashCode(), e2.hashCode());
    }

    @Test
    public void entryToStringIncludesEventKey() {
        ConversationLedgerEntry entry = ConversationLedgerEntry.builder()
                .role("user").content("c").stableType(LedgerStableType.USER_TASK)
                .sequence(1L).eventKey("my-key").build();

        assertThat(entry.toString()).contains("my-key");
    }

    @Test
    public void entryWithoutEventKeyToString() {
        ConversationLedgerEntry entry = ConversationLedgerEntry.builder()
                .role("user").content("c").stableType(LedgerStableType.USER_TASK)
                .sequence(1L).build();

        // Must not crash
        assertThat(entry.toString()).contains("ConversationLedgerEntry");
        assertNull(entry.eventKey());
    }

    // ================================================================
    // 16. Deduplication with mixed keys (null + non-null)
    // ================================================================

    @Test
    public void mixedNullAndNonNullEventKeys() {
        ConversationLedger ledger = new ConversationLedger();
        ledger.appendWithEventKey("user", "a", LedgerStableType.SYSTEM_NOTE, "key-1");
        ledger.appendWithEventKey("user", "b", LedgerStableType.SYSTEM_NOTE, null);
        ledger.appendWithEventKey("user", "c", LedgerStableType.SYSTEM_NOTE, null);
        ledger.appendWithEventKey("user", "d", LedgerStableType.SYSTEM_NOTE, "key-1"); // deduped
        ledger.appendWithEventKey("user", "e", LedgerStableType.SYSTEM_NOTE, "key-2");

        assertEquals(4, ledger.size());
        assertEquals("a", ledger.entries().get(0).content());
        assertEquals("b", ledger.entries().get(1).content());
        assertEquals("c", ledger.entries().get(2).content());
        assertEquals("e", ledger.entries().get(3).content());
    }

    // ================================================================
    // 17. Snapshot round-trip preserves event keys
    // ================================================================

    @Test
    public void snapshotRoundTripPreservesEventKeys() {
        AgentContext ctx = newContext("r-snap-key-1", "task");
        ctx.ensureLedgerActive();
        ctx.getConversationLedger().appendWithEventKey(
                "user", "hello", LedgerStableType.USER_TASK, "r-snap-key-1:init:user_task");
        ctx.getConversationLedger().appendWithEventKey(
                "assistant", "thinking", LedgerStableType.ASSISTANT_ACTION,
                "r-snap-key-1:1:assistant");

        AgentContextSnapshot snapshot = AgentContextSnapshot.from(ctx);
        AgentContext restored = snapshot.restore();

        List<ConversationLedgerEntry> entries = restored.getConversationLedger().entries();
        assertEquals(2, entries.size());
        assertEquals("r-snap-key-1:init:user_task", entries.get(0).eventKey());
        assertEquals("r-snap-key-1:1:assistant", entries.get(1).eventKey());

        // Dedup must work after restore
        restored.getConversationLedger().appendWithEventKey(
                "user", "hello-again", LedgerStableType.USER_TASK,
                "r-snap-key-1:init:user_task");
        assertEquals(2, restored.getConversationLedger().size());
    }
}
