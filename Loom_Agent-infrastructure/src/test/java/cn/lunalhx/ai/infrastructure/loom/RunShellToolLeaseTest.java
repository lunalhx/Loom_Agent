package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.agent.model.entity.AttemptLease;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.infrastructure.store.FileAttemptLeaseRepository;
import cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Ticket 09 contract: the Shell adapter re-validates Attempt Lease fence under
 * the Run lock and renews before the call completes. A lost fence cannot start
 * a process.
 */
public class RunShellToolLeaseTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void lostFenceCannotStartShell() throws Exception {
        Path workspace = Files.createTempDirectory("shell-lost-fence").toRealPath();
        FileAttemptLeaseRepository leases = new FileAttemptLeaseRepository(workspace, mapper);
        AttemptLease first = leases.tryAcquire("run-shell", "attempt-1").orElseThrow();
        assertTrue(leases.release("run-shell", first.getFence()));
        AttemptLease next = leases.tryAcquire("run-shell", "attempt-2").orElseThrow();
        assertNotEquals(first.getFence(), next.getFence());

        RunShellTool tool = new RunShellTool(new LocalWorkspacePort(), leases);
        ToolResult result = tool.call(call(workspace, "run-shell", first.getFence(),
                "touch started.txt"));
        assertFalse(result.isSuccess());
        assertTrue(result.getObservation().contains("attempt lease fence"));
        assertFalse(Files.exists(workspace.resolve("started.txt")));
    }

    @Test
    public void renewsLeaseBeforeCallCompletes() throws Exception {
        Path workspace = Files.createTempDirectory("shell-renew").toRealPath();
        FileAttemptLeaseRepository leases = new FileAttemptLeaseRepository(workspace, mapper);
        AttemptLease owned = leases.tryAcquire("run-shell", "attempt-1").orElseThrow();
        long before = owned.getHeartbeatEpochMilli();
        Thread.sleep(5);

        RunShellTool tool = new RunShellTool(new LocalWorkspacePort(), leases);
        tool.call(call(workspace, "run-shell", owned.getFence(), "echo recovered-shell"));

        AttemptLease after = leases.find("run-shell").orElseThrow();
        assertTrue(after.getHeartbeatEpochMilli() > before);
        assertTrue(leases.heartbeat("run-shell", owned.getFence()));
    }

    private ToolCall call(Path workspace, String runId, String fence, String command) {
        ExecutionProfile base = ExecutionProfile.forRun(CollaborationMode.BUILD, false)
                .withWorkspace(workspace);
        return ToolCall.builder()
                .name("run_shell")
                .runId(runId)
                .leaseFence(fence)
                .workspaceRoot(workspace)
                .executionProfile(base)
                .input(JsonNodeFactory.instance.objectNode().put("command", command))
                .build();
    }
}
