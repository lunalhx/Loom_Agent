package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentSession;
import cn.lunalhx.ai.domain.agent.model.entity.ResumeResult;
import cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/** Session open/resume coordination without a full CLI REPL. */
public class CliSessionBootstrapTest {

    @Test
    public void resumeRejectsWorkspaceMismatch() throws Exception {
        Path root = Files.createTempDirectory("cli-bootstrap");
        InMemorySessions store = new InMemorySessions();
        AgentSession existing = AgentSession.builder()
                .id("session-1")
                .schemaVersion(AgentSession.CURRENT_SCHEMA_VERSION)
                .workspaceRoot(root.resolve("other").toString())
                .collaborationMode(CollaborationMode.BUILD)
                .createdAt(Instant.now())
                .workingMemory(new WorkingContextMemory())
                .keyFiles(new LinkedHashMap<>())
                .build();
        store.save(existing);

        CliSessionBootstrap bootstrap = new CliSessionBootstrap(
                store, root.toString(), null);
        ResumeResult result = bootstrap.resume("session-1");

        assertEquals(ResumeResult.Kind.WORKSPACE_MISMATCH, result.getKind());
        assertSame(existing, result.getSession());
    }

    @Test
    public void createFreshSessionPersistsBuildMode() throws Exception {
        Path root = Files.createTempDirectory("cli-bootstrap-fresh");
        InMemorySessions store = new InMemorySessions();
        CliSessionBootstrap bootstrap = new CliSessionBootstrap(store, root.toString(), null);

        AgentSession created = bootstrap.createFreshSession("session-new", CollaborationMode.PLAN);

        assertEquals(CollaborationMode.PLAN, created.getCollaborationMode());
        assertEquals(root.toString(), created.getWorkspaceRoot());
        assertEquals(created, store.find("session-new").orElseThrow());
    }

    private static final class InMemorySessions implements AgentSessionRepository {
        private final java.util.Map<String, AgentSession> byId = new java.util.HashMap<>();

        @Override
        public AgentSession save(AgentSession session) {
            byId.put(session.getId(), session);
            return session;
        }

        @Override
        public boolean saveIfUnchanged(AgentSession session, Instant expectedUpdatedAt) {
            byId.put(session.getId(), session);
            return true;
        }

        @Override
        public AutoCloseable acquireExclusive(String sessionId) {
            return () -> {};
        }

        @Override
        public Optional<AgentSession> find(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<AgentSession> findLatest(String workspaceRoot) {
            return byId.values().stream().findFirst();
        }

        @Override
        public void delete(String sessionId) {
            byId.remove(sessionId);
        }
    }
}
