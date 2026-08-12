package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.tool.model.ExecutionGrant;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.FilesystemAccess;
import cn.lunalhx.ai.domain.tool.model.GrantLifetime;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BubblewrapSandboxBackendTest {
    @Test
    public void commandUsesAnOfflineEmptyRootWithOnlyDeclaredMounts() throws Exception {
        Path workspace = Files.createTempDirectory("bwrap-workspace").toRealPath();
        Path external = Files.createTempDirectory("bwrap-external").toRealPath();
        ExecutionProfile base = ExecutionProfile.forRun(CollaborationMode.BUILD, false).withWorkspace(workspace);
        ExecutionProfile profile = base.withExternalGrants(List.of(new ExecutionGrant(external,
                FilesystemAccess.READ, GrantLifetime.SESSION)));

        List<String> argv = BubblewrapSandboxBackend.command(profile, List.of("/bin/sh", "-c", "pwd"));
        assertTrue(argv.contains("--unshare-net"));
        assertTrue(argv.contains("--unshare-pid"));
        assertTrue(argv.contains("--ro-bind"));
        assertTrue(argv.contains(external.toString()));
        assertFalse(argv.contains(System.getProperty("user.home")));
    }
}
