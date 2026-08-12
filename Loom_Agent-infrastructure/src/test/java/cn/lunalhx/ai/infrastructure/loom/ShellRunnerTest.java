package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.tool.model.ShellExecutionResult;
import org.junit.Test;

import java.nio.file.Files;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.agent.model.entity.RootRunSecurityScope;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ShellRunnerTest {

    @Test
    public void returnsStructuredExitStatusAndBoundsOutputWhileDraining() throws Exception {
        ShellRunner.ShellResult success = ShellRunner.run("printf ok", Files.createTempDirectory("shell-run"), 2, Set.of(),
                ExecutionProfile.fullAccess(Files.createTempDirectory("shell-success-full")));
        assertEquals(0, success.execution().exitCode());
        assertEquals(ShellExecutionResult.TerminationReason.EXITED, success.execution().terminationReason());
        assertEquals("ok", success.stdout());

        ShellRunner.ShellResult supervised = ShellRunner.run("printf grouped", Files.createTempDirectory("shell-group"),
                2, Set.of(), ExecutionProfile.fullAccess(Files.createTempDirectory("shell-full")));
        assertEquals(supervised.stderr(), 0, supervised.execution().exitCode());
        assertEquals("grouped", supervised.stdout());

        long started = System.nanoTime();
        ShellRunner.ShellResult timedOut = ShellRunner.run("sleep 5", Files.createTempDirectory("shell-timeout"),
                1, Set.of(), ExecutionProfile.fullAccess(Files.createTempDirectory("shell-timeout-full")));
        assertEquals(ShellExecutionResult.TerminationReason.TIMED_OUT, timedOut.execution().terminationReason());
        assertTrue("timeout cleanup must finish promptly", TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 4);

        ShellRunner.ShellResult background = ShellRunner.run("sleep 5 &", Files.createTempDirectory("shell-background"),
                2, Set.of(), ExecutionProfile.fullAccess(Files.createTempDirectory("shell-background-full")));
        assertTrue(background.execution().backgroundProcessTerminated());

        RootRunSecurityScope scope = RootRunSecurityScope.create();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> ShellRunner.run("sleep 5", Files.createTempDirectory("shell-cancel"),
                    10, Set.of(), ExecutionProfile.fullAccess(Files.createTempDirectory("shell-cancel-full")), scope));
            Thread.sleep(150);
            scope.cancel();
            assertEquals(ShellExecutionResult.TerminationReason.CANCELLED,
                    future.get(3, TimeUnit.SECONDS).execution().terminationReason());
        } finally {
            scope.close();
        }

        ShellRunner.ShellResult noisy = ShellRunner.run("yes x | head -c 1100000", Files.createTempDirectory("shell-noisy"), 2, Set.of(),
                ExecutionProfile.fullAccess(Files.createTempDirectory("shell-noisy-full")));
        assertTrue(noisy.execution().stdoutTruncated());
        assertTrue(noisy.stdout().contains("[output truncated]"));
    }
}
