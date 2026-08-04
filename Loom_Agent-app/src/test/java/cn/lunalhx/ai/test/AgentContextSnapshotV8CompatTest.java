package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistory;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryEntry;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory;
import cn.lunalhx.ai.domain.agent.model.valobj.ConversationEntryType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * v8 snapshot compatibility: old snapshots carry a two-field {@link StablePrefix}
 * and flat {@code List<String>} working-memory notes. Restore must normalize them.
 */
public class AgentContextSnapshotV8CompatTest {

    private ObjectMapper mapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    public void legacyStringNotesAreNormalizedToStructuredNotes() throws Exception {
        String json = """
                {
                  "schemaVersion": 8,
                  "runId": "run-1",
                  "question": "task",
                  "conversationId": "conv-1",
                  "generation": 0,
                  "ledgerNextSequence": 2,
                  "ledgerEntries": [
                    {"entryId":"e1","sequence":0,"role":"user","content":"task","stableType":"USER_TASK","eventKey":"run-1:init:user_task"}
                  ],
                  "stablePrefix": {"frozenContent":"prefix","fingerprint":"fp"},
                  "workingMemory": {
                    "taskSummary":"summary",
                    "recentFiles":["A.java"],
                    "notes": ["note one", "note two"]
                  }
                }
                """;
        AgentContextSnapshot snapshot = mapper().readValue(json, AgentContextSnapshot.class);

        assertEquals(8, snapshot.getSchemaVersion());
        // Two-field prefix preserved and flagged legacy.
        assertTrue(snapshot.getStablePrefix().isLegacyTwoField());
        assertEquals("fp", snapshot.getStablePrefix().fingerprint());

        // Legacy string notes normalized into structured MemoryNotes.
        WorkingContextMemory wm = snapshot.getWorkingMemory();
        assertFalse(wm.notes().isEmpty());
        assertEquals(2, wm.notes().size());
        assertEquals("note one", wm.notes().get(0).text());
        assertEquals("note two", wm.notes().get(1).text());

        // Ledger restored append-only.
        AgentContext ctx = snapshot.restore();
        ConversationHistory history = ctx.getConversationHistory();
        assertEquals(1, history.size());
        List<ConversationHistoryEntry> entries = history.entries();
        assertEquals("task", entries.get(0).content());
        assertEquals(ConversationEntryType.USER_TASK, entries.get(0).stableType());
        // Structured notes preserved through restore.
        assertEquals(2, ctx.getWorkingMemory().notes().size());
    }

    @Test
    public void newStructuredNotesRoundTrip() throws Exception {
        WorkingContextMemory wm = new WorkingContextMemory();
        wm.addNote("structured note", List.of("tag1"), "A.java", "process");
        wm.addNote("plain note");

        String json = mapper().writeValueAsString(wm);
        WorkingContextMemory restored = mapper().readValue(json, WorkingContextMemory.class);

        assertEquals(2, restored.notes().size());
        assertEquals(List.of("tag1"), restored.notes().get(0).tags());
        assertEquals("A.java", restored.notes().get(0).source());
        assertEquals("plain note", restored.notes().get(1).text());
    }
}
