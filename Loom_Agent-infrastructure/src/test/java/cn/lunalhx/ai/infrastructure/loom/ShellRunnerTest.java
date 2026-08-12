package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.tool.model.ShellExecutionResult;
import org.junit.Test;

import java.nio.file.Files;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ShellRunnerTest {

    @Test
    public void returnsStructuredExitStatusAndBoundsOutputWhileDraining() throws Exception {
        ShellRunner.ShellResult success = ShellRunner.run("printf ok", Files.createTempDirectory("shell-run"), 2, Set.of());
        assertEquals(0, success.execution().exitCode());
        assertEquals(ShellExecutionResult.TerminationReason.EXITED, success.execution().terminationReason());
        assertEquals("ok", success.stdout());

        ShellRunner.ShellResult noisy = ShellRunner.run("yes x | head -c 1100000", Files.createTempDirectory("shell-noisy"), 2, Set.of());
        assertTrue(noisy.execution().stdoutTruncated());
        assertTrue(noisy.stdout().contains("[output truncated]"));
    }
}
