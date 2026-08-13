package cn.lunalhx.ai.infrastructure.store;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AgentSession;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryDocument;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Ticket 02 repository seam: obsolete Session v4, TaskCheckpoint payloads,
 * AgentCheckpoint v14, and unversioned Runs are rejected without migration.
 */
public class PersistenceSchemaRejectionTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void sessionV4IsRejectedAsIncompatible() throws Exception {
        Path workspace = Files.createTempDirectory("schema-session-v4");
        Path sessions = Files.createDirectories(workspace.resolve(".loom-code").resolve("sessions"));
        Files.writeString(sessions.resolve("old.json"), """
                {"id":"old","schemaVersion":4,"workspaceRoot":"%s","collaborationMode":"BUILD",
                "history":[],"checkpoint":{"schemaVersion":2,"runModeSnapshot":"BUILD"}}
                """.formatted(workspace));

        try {
            new FileAgentSessionRepository(workspace, mapper).find("old");
            fail("expected Session v4 rejection");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("incompatible"));
            assertTrue(e.getMessage().contains("4") || e.getMessage().toLowerCase().contains("schema"));
        }
        assertTrue(Files.readString(sessions.resolve("old.json")).contains("\"schemaVersion\":4"));
    }

    @Test
    public void sessionContainingTaskCheckpointIsRejected() throws Exception {
        Path workspace = Files.createTempDirectory("schema-task-checkpoint");
        Path sessions = Files.createDirectories(workspace.resolve(".loom-code").resolve("sessions"));
        Files.writeString(sessions.resolve("with-tc.json"), """
                {"id":"with-tc","schemaVersion":5,"workspaceRoot":"%s","collaborationMode":"BUILD",
                "checkpoint":{"schemaVersion":2,"runModeSnapshot":"BUILD","goal":"x"}}
                """.formatted(workspace));

        try {
            new FileAgentSessionRepository(workspace, mapper).find("with-tc");
            fail("expected TaskCheckpoint rejection");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().toLowerCase().contains("checkpoint")
                    || e.getMessage().toLowerCase().contains("incompatible"));
        }
    }

    @Test
    public void agentCheckpointV14IsRejected() throws Exception {
        Path workspace = Files.createTempDirectory("schema-cp-v14");
        Path dir = Files.createDirectories(workspace.resolve(".loom-code").resolve("checkpoints").resolve("run-1"));
        Files.writeString(dir.resolve("1.json"), """
                {"runId":"run-1","version":1,"currentNode":"prompt_build",
                "contextSnapshot":{"schemaVersion":14,"runId":"run-1","runModeSnapshot":"BUILD",
                "ledgerEntries":[],"ledgerNextSequence":0}}
                """);

        try {
            new FileAgentCheckpointRepository(workspace, mapper).latest("run-1");
            fail("expected AgentCheckpoint v14 rejection");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("incompatible"));
        }
    }

    @Test
    public void unversionedRunIsRejected() throws Exception {
        Path workspace = Files.createTempDirectory("schema-run-unversioned");
        Path dir = Files.createDirectories(workspace.resolve(".loom-code").resolve("runs").resolve("run-1"));
        Files.writeString(dir.resolve("run.json"), """
                {"runId":"run-1","sessionId":"s1","status":"COMPLETED","question":"hi"}
                """);

        try {
            new FileAgentRunRepository(workspace, mapper).find("run-1")
                    .orElseThrow(() -> new IllegalArgumentException("missing"));
            // find must throw rather than return an unversioned run
            fail("expected unversioned Run rejection");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().toLowerCase().contains("incompatible")
                    || e.getMessage().toLowerCase().contains("schema"));
        }
    }

    @Test
    public void currentSchemasAreAccepted() throws Exception {
        Path workspace = Files.createTempDirectory("schema-current-ok");
        FileAgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        sessions.save(AgentSession.builder()
                .id("s1")
                .schemaVersion(AgentSession.CURRENT_SCHEMA_VERSION)
                .workspaceRoot(workspace.toString())
                .collaborationMode(CollaborationMode.BUILD)
                .createdAt(Instant.now())
                .build());

        FileConversationHistoryRepository histories =
                new FileConversationHistoryRepository(workspace, mapper);
        histories.save(ConversationHistoryDocument.builder()
                .schemaVersion(ConversationHistoryDocument.CURRENT_SCHEMA_VERSION)
                .sessionId("s1")
                .entries(java.util.List.of())
                .nextSequence(0L)
                .build());

        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        runs.save(AgentRun.builder()
                .schemaVersion(AgentRun.CURRENT_SCHEMA_VERSION)
                .runId("run-1")
                .sessionId("s1")
                .status(cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus.COMPLETED)
                .build());

        assertTrue(sessions.find("s1").isPresent());
        assertTrue(histories.find("s1").isPresent());
        assertTrue(runs.find("run-1").isPresent());
    }
}
