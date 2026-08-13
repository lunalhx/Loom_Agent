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
 * Obsolete checkpoint shapes are rejected after ticket 02 moves the persisted
 * checkpoint directly to the mode-and-evidence-and-binding-bearing v12 shape.
 */
public class AgentContextSnapshotV8CompatTest {

    private ObjectMapper mapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    public void obsoleteSnapshotShapeIsRejected() throws Exception {
        String json = """
                {
                  "schemaVersion": 8,
                  "runId": "run-1",
                  "question": "task",
                  "conversationId": "conv-1",
                  "generation": 0,
                  "stablePrefix": {"frozenContent":"prefix","fingerprint":"fp"},
                  "workingMemory": {
                    "taskSummary":"summary",
                    "recentFiles":["A.java"],
                    "notes": []
                  }
                }
                """;
        AgentContextSnapshot snapshot = mapper().readValue(json, AgentContextSnapshot.class);

        try {
            snapshot.restore();
            throw new AssertionError("expected obsolete checkpoint schema rejection");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("incompatible schema"));
        }
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
