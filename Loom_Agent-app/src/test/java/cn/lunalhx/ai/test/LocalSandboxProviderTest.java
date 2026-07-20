package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.common.LoomPaths;
import cn.lunalhx.ai.domain.tool.sandbox.SandboxLease;
import cn.lunalhx.ai.domain.tool.sandbox.SandboxRequest;
import cn.lunalhx.ai.domain.tool.model.BackgroundLaunchMode;
import cn.lunalhx.ai.infrastructure.tool.BackgroundProcessManager;
import cn.lunalhx.ai.infrastructure.tool.LocalSandboxProvider;
import cn.lunalhx.ai.infrastructure.tool.SandboxEnvPolicy;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LocalSandboxProviderTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void conversationUsesReferenceCountedSharedSandbox() throws Exception {
        try (Fixture fixture = fixture()) {
            SandboxRequest request = request(fixture.workspace, "conversation-1");
            SandboxLease first = fixture.provider.acquire(request);
            SandboxLease second = fixture.provider.acquire(request);
            assertSame(first.sandbox(), second.sandbox());
            assertEquals(2, fixture.provider.referenceCount("conversation-1"));
            first.close();
            second.close();
            assertEquals(0, fixture.provider.referenceCount("conversation-1"));
        }
    }

    @Test
    public void conversationsShareWorkspaceButNotSessionDirectories() throws Exception {
        try (Fixture fixture = fixture();
             SandboxLease first = fixture.provider.acquire(request(fixture.workspace, "conversation-a"));
             SandboxLease second = fixture.provider.acquire(request(fixture.workspace, "conversation-b"))) {
            assertEquals(first.sandbox().workspaceMapping().hostPath(), second.sandbox().workspaceMapping().hostPath());
            assertFalse(first.sandbox().sessionTemp().equals(second.sandbox().sessionTemp()));
        }
    }

    @Test
    public void endingConversationDeletesOnlySessionState() throws Exception {
        try (Fixture fixture = fixture()) {
            Files.writeString(fixture.workspace.resolve("keep.txt"), "keep");
            Path session;
            try (SandboxLease lease = fixture.provider.acquire(request(fixture.workspace, "conversation-end"))) {
                session = lease.sandbox().sessionTemp();
                Files.writeString(session.resolve("temporary.txt"), "temporary");
            }
            fixture.provider.endConversation("conversation-end");
            assertTrue(Files.exists(fixture.workspace.resolve("keep.txt")));
            assertFalse(Files.exists(session));
        }
    }

    @Test
    public void conversationCannotSwitchWorkspace() throws Exception {
        try (Fixture fixture = fixture();
             SandboxLease ignored = fixture.provider.acquire(request(fixture.workspace, "conversation-fixed"))) {
            Path other = temporaryFolder.newFolder("other-workspace").toPath();
            assertThrows(IllegalStateException.class,
                    () -> fixture.provider.acquire(request(other, "conversation-fixed")));
        }
    }

    @Test
    public void endingOneConversationDoesNotCancelAnotherConversation() throws Exception {
        try (Fixture fixture = fixture();
             SandboxLease first = fixture.provider.acquire(request(fixture.workspace, "conversation-process-a"));
             SandboxLease second = fixture.provider.acquire(request(fixture.workspace, "conversation-process-b"))) {
            var firstTask = first.sandbox().startBackground(List.of("/bin/sh", "-c", "sleep 5"), fixture.workspace,
                    java.util.Map.of(), 5000, "run-a", "workspace", BackgroundLaunchMode.EXPLICIT);
            second.sandbox().startBackground(List.of("/bin/sh", "-c", "sleep 5"), fixture.workspace,
                    java.util.Map.of(), 5000, "run-b", "workspace", BackgroundLaunchMode.EXPLICIT);
            assertTrue(Path.of(firstTask.task().getStdoutFile()).startsWith(first.sandbox().sessionTemp()));
            assertTrue(first.sandbox().hasActiveProcesses());
            assertTrue(second.sandbox().hasActiveProcesses());

            fixture.provider.endConversation("conversation-process-a");

            assertFalse(first.sandbox().hasActiveProcesses());
            assertTrue(second.sandbox().hasActiveProcesses());
        }
    }

    @Test
    public void sessionDirectoriesCannotEscapeThroughSymlinks() throws Exception {
        Path home = temporaryFolder.newFolder("sandbox-symlink-home").toPath();
        Path outside = temporaryFolder.newFolder("sandbox-symlink-outside").toPath();
        Path workspace = temporaryFolder.newFolder("sandbox-symlink-workspace").toPath();
        Files.createDirectories(home.resolve("conversations"));
        Files.createSymbolicLink(home.resolve("conversations/escaped"), outside);
        SandboxEnvPolicy policy = new SandboxEnvPolicy(SandboxEnvPolicy.Mode.BLACKLIST, Set.of(), Set.of());
        BackgroundProcessManager manager = new BackgroundProcessManager(home.resolve("tasks"),
                1000, 100, 2000, 4, 2, 1, null, policy);
        try (LocalSandboxProvider provider = new LocalSandboxProvider(manager, new LoomPaths(home), 8, 60000)) {
            assertThrows(IllegalStateException.class,
                    () -> provider.acquire(request(workspace, "escaped")));
            assertFalse(Files.exists(outside.resolve("tmp")));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void backgroundLogsCannotEscapeSessionThroughSymlinks() throws Exception {
        try (Fixture fixture = fixture();
             SandboxLease lease = fixture.provider.acquire(request(fixture.workspace, "conversation-log-link"))) {
            Path outside = temporaryFolder.newFolder("background-log-outside").toPath();
            Files.createSymbolicLink(lease.sandbox().sessionTemp().resolve("background"), outside);

            var result = lease.sandbox().startBackground(List.of("/bin/sh", "-c", "echo escaped"),
                    fixture.workspace, java.util.Map.of(), 1000, "run-link", "workspace",
                    BackgroundLaunchMode.EXPLICIT);

            assertFalse(result.started());
            try (var files = Files.list(outside)) {
                assertEquals(0, files.count());
            }
        }
    }

    private Fixture fixture() throws Exception {
        Path home = temporaryFolder.newFolder("loom-home-" + System.nanoTime()).toPath();
        Path workspace = temporaryFolder.newFolder("workspace-" + System.nanoTime()).toPath();
        SandboxEnvPolicy policy = new SandboxEnvPolicy(SandboxEnvPolicy.Mode.BLACKLIST, Set.of(), Set.of());
        BackgroundProcessManager manager = new BackgroundProcessManager(home.resolve("tasks"),
                1000, 100, 2000, 4, 2, 1, null, policy);
        LocalSandboxProvider provider = new LocalSandboxProvider(manager, new LoomPaths(home), 8, 60000);
        return new Fixture(workspace, manager, provider);
    }

    private SandboxRequest request(Path workspace, String conversationId) {
        return new SandboxRequest(workspace, conversationId, 2000, Set.of("PATH"));
    }

    private record Fixture(Path workspace,
                           BackgroundProcessManager manager,
                           LocalSandboxProvider provider) implements AutoCloseable {
        @Override
        public void close() {
            provider.close();
            manager.shutdown();
        }
    }
}
