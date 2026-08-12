package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SeatbeltSandboxBackendTest {

    @Test
    public void policyMountsOnlyTheCanonicalWorkspaceAndSystemRuntimeRoots() throws Exception {
        Path workspace = Files.createTempDirectory("seatbelt-workspace").toRealPath();
        String policy = SeatbeltSandboxBackend.policy(ExecutionProfile.forRun(CollaborationMode.BUILD, false)
                .withWorkspace(workspace));

        assertTrue(policy.contains("(subpath \"" + workspace + "\")"));
        assertTrue(policy.contains("(subpath \"/System\")"));
        assertFalse(policy.contains("(subpath \"/private\")"));
        assertFalse(policy.contains("\\\\\""));
    }

    @Test
    public void policyMountsExternalGrantsWithTheirDeclaredAccess() throws Exception {
        Path workspace = Files.createTempDirectory("seatbelt-workspace").toRealPath();
        Path external = Files.createTempDirectory("seatbelt-external").toRealPath();
        ExecutionProfile base = ExecutionProfile.forRun(CollaborationMode.BUILD, false).withWorkspace(workspace);
        ExecutionProfile profile = new ExecutionProfile(base.kind(), base.workspace(), base.workspaceAccess(),
                base.homeRoot(), base.temporaryRoot(), base.networkAllowed(), base.hostPrivateVisible(),
                List.of(new cn.lunalhx.ai.domain.tool.model.ExecutionGrant(external,
                        cn.lunalhx.ai.domain.tool.model.FilesystemAccess.READ,
                        cn.lunalhx.ai.domain.tool.model.GrantLifetime.SESSION)), base.sandboxBackend());

        String policy = SeatbeltSandboxBackend.policy(profile);
        assertTrue(policy.contains("(allow file-read* (subpath \"" + external + "\"))"));
        assertFalse(policy.contains("(allow file-write* (subpath \"" + external + "\"))"));
    }
}
