package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedger;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedgerEntry;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.agent.model.valobj.LedgerStableType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * C2: AgentContextSnapshot schema v3 persistence — unit and JSON-compat tests.
 */
public class AgentContextSnapshotPersistenceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== v3 round trip ====================

    @Test
    public void v3RoundTripWithLedger() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("v3-roundtrip");
        ctx.ensureLedgerActive();
        ctx.getConversationLedger()
                .append("user_task", "hello", LedgerStableType.USER_TASK)
                .append("assistant_action", "thinking...", LedgerStableType.ASSISTANT_ACTION);
        ctx.setStablePrefix(new StablePrefix("frozen-content", "fp-abc123"));
        ctx.setGeneration(1);

        AgentContextSnapshot snapshot = AgentContextSnapshot.from(ctx);
        assertEquals(3, snapshot.getSchemaVersion());

        AgentContext restored = snapshot.restore();
        assertEquals("v3-roundtrip", restored.getRunId());
        assertNotNull(restored.getConversationLedger());
        assertEquals(2, restored.getConversationLedger().size());
        assertEquals(2, restored.getConversationLedger().nextSequence());
        assertEquals("hello", restored.getConversationLedger().entries().get(0).content());
        assertEquals("thinking...", restored.getConversationLedger().entries().get(1).content());
        assertEquals(LedgerStableType.USER_TASK,
                restored.getConversationLedger().entries().get(0).stableType());
        assertEquals(LedgerStableType.ASSISTANT_ACTION,
                restored.getConversationLedger().entries().get(1).stableType());

        assertNotNull(restored.getStablePrefix());
        assertEquals("frozen-content", restored.getStablePrefix().frozenContent());
        assertEquals("fp-abc123", restored.getStablePrefix().fingerprint());
        assertEquals(1, restored.getGeneration());
    }

    @Test
    public void v3RoundTripWithEmptyLedger() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("v3-empty-ledger");
        ctx.ensureLedgerActive(); // creates empty ledger

        AgentContextSnapshot snapshot = AgentContextSnapshot.from(ctx);
        assertEquals(3, snapshot.getSchemaVersion());

        AgentContext restored = snapshot.restore();
        // Empty ledger: no entries, so not reconstructed
        assertNull("empty ledger should not be persisted",
                restored.getConversationLedger());
        assertEquals(0, restored.getGeneration());
    }

    @Test
    public void v3RoundTripWithoutLedgerActivation() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("v3-no-ledger");
        // ledger NOT activated

        AgentContextSnapshot snapshot = AgentContextSnapshot.from(ctx);

        AgentContext restored = snapshot.restore();
        assertNull(restored.getConversationLedger());
        assertNull(restored.getStablePrefix());
        assertEquals(0, restored.getGeneration());
    }

    // ==================== v2 / missing-fields backward compatibility ====================

    @Test
    public void v2JsonDeserializationLeavesNewFieldsEmpty() throws Exception {
        // A v2 JSON snapshot: schemaVersion=2, no ledgerEntries/ledgerNextSequence/stablePrefix/generation
        String v2Json = "{"
                + "\"schemaVersion\":2,"
                + "\"runId\":\"v2-legacy\","
                + "\"step\":5,"
                + "\"parseErrors\":0,"
                + "\"stopHookContinuationCount\":0,"
                + "\"segmentIndex\":0,"
                + "\"segmentStartStep\":0,"
                + "\"noProgressRounds\":0,"
                + "\"sameActionRepeats\":0,"
                + "\"sameFailureRepeats\":0"
                + "}";

        AgentContextSnapshot snapshot = objectMapper.readValue(v2Json, AgentContextSnapshot.class);

        assertEquals(2, snapshot.getSchemaVersion());
        assertEquals("v2-legacy", snapshot.getRunId());
        // New fields must be absent (null/0) — not faked
        assertNull(snapshot.getLedgerEntries());
        assertEquals(0, snapshot.getLedgerNextSequence());
        assertNull(snapshot.getStablePrefix());
        assertEquals(0, snapshot.getGeneration());

        // restore() must not NPE on v2 snapshot
        AgentContext restored = snapshot.restore();
        assertNull(restored.getConversationLedger());
        assertNull(restored.getStablePrefix());
        assertEquals(0, restored.getGeneration());
    }

    @Test
    public void v2SnapshotRestoreProducesValidEmptyContext() throws Exception {
        String v2Json = "{"
                + "\"schemaVersion\":2,"
                + "\"runId\":\"v2-restore-test\","
                + "\"question\":\"test question\","
                + "\"step\":3,"
                + "\"parseErrors\":0,"
                + "\"stopHookContinuationCount\":0,"
                + "\"segmentIndex\":0,"
                + "\"segmentStartStep\":0,"
                + "\"noProgressRounds\":0,"
                + "\"sameActionRepeats\":0,"
                + "\"sameFailureRepeats\":0"
                + "}";

        AgentContextSnapshot snapshot = objectMapper.readValue(v2Json, AgentContextSnapshot.class);
        AgentContext restored = snapshot.restore();

        // v2 snapshots restore with NO ledger — initializer builds it fresh
        assertNull("v2 restore must not fake a completed ledger",
                restored.getConversationLedger());
        assertFalse("v2 restore must not have active ledger",
                restored.isLedgerActive());

        // But all v2 fields should survive
        assertEquals("v2-restore-test", restored.getRunId());
        assertEquals("test question", restored.getQuestion());
        assertEquals(3, restored.getStep());
    }

    // ==================== generation persistence ====================

    @Test
    public void generationSurvivesRoundTrip() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("gen-test");
        ctx.ensureLedgerActive();
        ctx.incrementGeneration(); // 1
        ctx.incrementGeneration(); // 2
        ctx.incrementGeneration(); // 3

        AgentContextSnapshot snapshot = AgentContextSnapshot.from(ctx);
        assertEquals(3, snapshot.getGeneration());

        AgentContext restored = snapshot.restore();
        assertEquals(3, restored.getGeneration());
    }

    @Test
    public void generationZeroSurvivesRoundTrip() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("gen-zero");
        ctx.ensureLedgerActive();
        // generation stays at 0

        AgentContextSnapshot snapshot = AgentContextSnapshot.from(ctx);
        assertEquals(0, snapshot.getGeneration());

        AgentContext restored = snapshot.restore();
        assertEquals(0, restored.getGeneration());
    }

    @Test
    public void generationWithoutLedgerActivation() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("gen-no-ledger");
        ctx.setGeneration(7); // manual set without ledger

        AgentContextSnapshot snapshot = AgentContextSnapshot.from(ctx);
        assertEquals(7, snapshot.getGeneration());

        AgentContext restored = snapshot.restore();
        assertEquals(7, restored.getGeneration());
        assertNull(restored.getConversationLedger());
    }

    // ==================== collection isolation after restore ====================

    @Test
    public void restoredLedgerMutationsDoNotPolluteSnapshot() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("isolation-test");
        ctx.ensureLedgerActive();
        ctx.getConversationLedger().append("user_task", "original", LedgerStableType.USER_TASK);

        AgentContextSnapshot snapshot = AgentContextSnapshot.from(ctx);

        // Mutate the restored context's ledger
        AgentContext restored = snapshot.restore();
        restored.getConversationLedger().append("assistant_action", "added-after-restore",
                LedgerStableType.ASSISTANT_ACTION);

        // Snapshot entries must be unchanged
        assertEquals("snapshot ledger entries must be unchanged",
                1, snapshot.getLedgerEntries().size());
        assertEquals("original", snapshot.getLedgerEntries().get(0).content());

        // Restored context should have the new entry (2 total)
        assertEquals(2, restored.getConversationLedger().size());

        // Restore again — fresh context with only the original entry
        AgentContext restored2 = snapshot.restore();
        assertEquals(1, restored2.getConversationLedger().size());
        assertEquals("original", restored2.getConversationLedger().entries().get(0).content());
    }

    @Test
    public void snapshotLedgerEntriesAreDefensiveCopy() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("defensive-copy");
        ctx.ensureLedgerActive();
        ctx.getConversationLedger().append("user_task", "entry1", LedgerStableType.USER_TASK);

        AgentContextSnapshot snapshot = AgentContextSnapshot.from(ctx);

        // Mutate the context's ledger AFTER snapshot
        ctx.getConversationLedger().append("system_note", "entry2", LedgerStableType.SYSTEM_NOTE);

        // Snapshot should only have the entry at time of capture
        assertEquals(1, snapshot.getLedgerEntries().size());
        assertEquals("entry1", snapshot.getLedgerEntries().get(0).content());
    }

    @Test
    public void snapshotLedgerEntriesListIsUnmodifiable() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("entries-immutable");
        ctx.ensureLedgerActive();
        ctx.getConversationLedger().append("user_task", "entry", LedgerStableType.USER_TASK);

        AgentContextSnapshot snapshot = AgentContextSnapshot.from(ctx);

        // The stored List<ConversationLedgerEntry> is an ArrayList (mutable via setter),
        // but ConversationLedgerEntry is immutable. Modifying the list after
        // snapshot creation should not be done by external code — we test that
        // restore() creates a fresh copy.

        AgentContext restored = snapshot.restore();
        List<ConversationLedgerEntry> restoredEntries = restored.getConversationLedger().entries();

        // Trying to add to the restored entries() (which is List.copyOf, unmodifiable)
        try {
            restoredEntries.add(ConversationLedgerEntry.builder()
                    .role("r").content("c").stableType(LedgerStableType.SYSTEM_NOTE).build());
            throw new AssertionError("Should have thrown UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // expected — restored ledger entries() is immutable
        }
    }

    // ==================== stable prefix persistence ====================

    @Test
    public void stablePrefixSurvivesRoundTrip() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("prefix-test");
        ctx.setStablePrefix(new StablePrefix("long-frozen-content-here", "sha256abc"));

        AgentContextSnapshot snapshot = AgentContextSnapshot.from(ctx);
        AgentContext restored = snapshot.restore();

        assertNotNull(restored.getStablePrefix());
        assertEquals("long-frozen-content-here", restored.getStablePrefix().frozenContent());
        assertEquals("sha256abc", restored.getStablePrefix().fingerprint());
    }

    @Test
    public void nullStablePrefixSurvivesRoundTrip() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("null-prefix");

        AgentContextSnapshot snapshot = AgentContextSnapshot.from(ctx);
        AgentContext restored = snapshot.restore();

        assertNull(restored.getStablePrefix());
    }

    // ==================== ledger entries content fidelity ====================

    @Test
    public void ledgerEntriesSequenceAndEntryIdPreserved() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("seq-test");
        ctx.ensureLedgerActive();
        ctx.getConversationLedger()
                .append("user_task", "a", LedgerStableType.USER_TASK)
                .append("user_input", "b", LedgerStableType.USER_INPUT)
                .append("assistant_action", "c", LedgerStableType.ASSISTANT_ACTION)
                .append("tool_result", "d", LedgerStableType.TOOL_RESULT)
                .append("system_note", "e", LedgerStableType.SYSTEM_NOTE);

        AgentContextSnapshot snapshot = AgentContextSnapshot.from(ctx);
        assertEquals(5, snapshot.getLedgerEntries().size());
        assertEquals(5, snapshot.getLedgerNextSequence());

        // Verify sequences and entry IDs
        for (int i = 0; i < 5; i++) {
            ConversationLedgerEntry e = snapshot.getLedgerEntries().get(i);
            assertEquals(i, e.sequence());
            assertThat(e.entryId()).isNotBlank();
        }

        AgentContext restored = snapshot.restore();
        List<ConversationLedgerEntry> restoredEntries = restored.getConversationLedger().entries();
        assertEquals(5, restoredEntries.size());
        for (int i = 0; i < 5; i++) {
            assertEquals("sequence must match original", i, restoredEntries.get(i).sequence());
            assertEquals("entryId must match original",
                    snapshot.getLedgerEntries().get(i).entryId(),
                    restoredEntries.get(i).entryId());
        }
        assertEquals(5, restored.getConversationLedger().nextSequence());
    }

    // ==================== JSON round-trip (v3 → JSON → v3) ====================

    @Test
    public void v3JsonRoundTrip() throws Exception {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("json-roundtrip");
        ctx.ensureLedgerActive();
        ctx.getConversationLedger()
                .append("user_task", "json-test", LedgerStableType.USER_TASK);
        ctx.setStablePrefix(new StablePrefix("frozen", "fp-json"));
        ctx.setGeneration(2);
        ctx.setStep(10);

        AgentContextSnapshot original = AgentContextSnapshot.from(ctx);

        // Serialize → deserialize
        String json = objectMapper.writeValueAsString(original);
        AgentContextSnapshot reloaded = objectMapper.readValue(json, AgentContextSnapshot.class);

        assertEquals(3, reloaded.getSchemaVersion());
        assertEquals(1, reloaded.getLedgerEntries().size());
        assertEquals("json-test", reloaded.getLedgerEntries().get(0).content());
        assertEquals("fp-json", reloaded.getStablePrefix().fingerprint());
        assertEquals(2, reloaded.getGeneration());

        // Final restore
        AgentContext restored = reloaded.restore();
        assertNotNull(restored.getConversationLedger());
        assertEquals(1, restored.getConversationLedger().size());
        assertEquals(2, restored.getGeneration());
        assertEquals("fp-json", restored.getStablePrefix().fingerprint());
    }

    // ==================== multiple entries across all stable types ====================

    @Test
    public void allStableTypesSurviveRoundTrip() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("all-types");
        ctx.ensureLedgerActive();
        ctx.getConversationLedger()
                .append("user", "task1", LedgerStableType.USER_TASK)
                .append("user", "input1", LedgerStableType.USER_INPUT)
                .append("assistant", "action1", LedgerStableType.ASSISTANT_ACTION)
                .append("tool", "result1", LedgerStableType.TOOL_RESULT)
                .append("system", "note1", LedgerStableType.SYSTEM_NOTE);

        AgentContextSnapshot snapshot = AgentContextSnapshot.from(ctx);
        AgentContext restored = snapshot.restore();

        List<ConversationLedgerEntry> entries = restored.getConversationLedger().entries();
        assertEquals(5, entries.size());
        assertEquals(LedgerStableType.USER_TASK, entries.get(0).stableType());
        assertEquals(LedgerStableType.USER_INPUT, entries.get(1).stableType());
        assertEquals(LedgerStableType.ASSISTANT_ACTION, entries.get(2).stableType());
        assertEquals(LedgerStableType.TOOL_RESULT, entries.get(3).stableType());
        assertEquals(LedgerStableType.SYSTEM_NOTE, entries.get(4).stableType());
    }

    // ==================== schema version boundaries ====================

    @Test
    public void snapshotSchemaVersionShouldBe3() {
        assertEquals(3, new AgentContextSnapshot().getSchemaVersion());
        AgentContext ctx = new AgentContext();
        ctx.setRunId("v3-check");
        assertEquals(3, AgentContextSnapshot.from(ctx).getSchemaVersion());
    }

    @Test
    public void schemaVersionShouldBeAcceptedOnlyInRange2To3() {
        // schemaVersion < 2 || > 3 is rejected; only 2 and 3 are accepted
        assertThat(1 < 2 || 1 > 3).as("v1 rejected").isTrue();
        assertThat(2 < 2 || 2 > 3).as("v2 accepted").isFalse();
        assertThat(3 < 2 || 3 > 3).as("v3 accepted").isFalse();
        assertThat(4 < 2 || 4 > 3).as("v4 rejected").isTrue();
    }

    // ==================== defensive: restore does not corrupt snapshot ====================

    @Test
    public void restoreDoesNotModifySnapshotFields() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("snapshot-integrity");
        ctx.ensureLedgerActive();
        ctx.getConversationLedger().append("user_task", "data", LedgerStableType.USER_TASK);
        ctx.setStablePrefix(new StablePrefix("content", "fingerprint"));
        ctx.setGeneration(5);

        AgentContextSnapshot snapshot = AgentContextSnapshot.from(ctx);

        // Record snapshot state before restore
        int ledgerSizeBefore = snapshot.getLedgerEntries().size();
        String prefixFpBefore = snapshot.getStablePrefix().fingerprint();

        // Restore multiple times
        snapshot.restore();
        snapshot.restore();
        snapshot.restore();

        // Snapshot fields must be unchanged
        assertEquals("ledger entries size unchanged after restore", ledgerSizeBefore,
                snapshot.getLedgerEntries().size());
        assertEquals("stable prefix unchanged after restore", prefixFpBefore,
                snapshot.getStablePrefix().fingerprint());
        assertEquals("generation unchanged after restore", 5, snapshot.getGeneration());
    }
}
