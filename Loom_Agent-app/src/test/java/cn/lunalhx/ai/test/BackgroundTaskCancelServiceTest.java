package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.tool.adapter.port.BackgroundShellTaskRepository;
import cn.lunalhx.ai.domain.tool.model.BackgroundShellTask;
import cn.lunalhx.ai.domain.tool.model.BackgroundTaskStatus;
import cn.lunalhx.ai.domain.tool.service.BackgroundTaskCancelService;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryBackgroundShellTaskRepository;
import cn.lunalhx.ai.infrastructure.tool.BackgroundProcessManager;
import cn.lunalhx.ai.infrastructure.tool.SandboxEnvPolicy;
import cn.lunalhx.ai.service.DefaultBackgroundTaskCancelService;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BackgroundTaskCancelServiceTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private BackgroundShellTaskRepository taskRepository;
    private BackgroundProcessManager processManager;
    private BackgroundTaskCancelService cancelService;

    @Before
    public void setUp() throws Exception {
        taskRepository = new InMemoryBackgroundShellTaskRepository();
        Path logDir = temporaryFolder.newFolder("bg-logs").toPath();
        SandboxEnvPolicy envPolicy = new SandboxEnvPolicy(
                SandboxEnvPolicy.Mode.BLACKLIST, Set.of(), Set.of());
        processManager = new BackgroundProcessManager(logDir, 120_000, 10_000, 600_000, 10, 5, 2, taskRepository, envPolicy);
        cancelService = new DefaultBackgroundTaskCancelService(taskRepository, processManager);
    }

    @Test
    public void cancelNotFoundShouldReturnNotFound() {
        BackgroundTaskCancelService.CancelResult result = cancelService.cancel("r-1", "nonexistent");
        assertFalse(result.success());
        assertEquals("BACKGROUND_TASK_NOT_FOUND", result.errorCode());
    }

    @Test
    public void cancelRunningTaskWithProcessShouldSetCancelled() throws Exception {
        BackgroundProcessManager.BackgroundStartResult bgResult = processManager.startBackground(
                java.util.List.of("sleep", "60"), temporaryFolder.getRoot().toPath(), 120_000,
                "r-1", "c-1", "ws", cn.lunalhx.ai.domain.tool.model.BackgroundLaunchMode.EXPLICIT);
        assertTrue(bgResult.started());
        String taskId = bgResult.task().getTaskId();

        BackgroundTaskCancelService.CancelResult result = cancelService.cancel("r-1", taskId);
        assertTrue(result.success());
        assertEquals(BackgroundTaskStatus.CANCELLED, result.status());

        BackgroundShellTask savedTask = taskRepository.find(taskId).orElse(null);
        assertNotNull(savedTask);
        assertEquals(BackgroundTaskStatus.CANCELLED, savedTask.getStatus());
    }

    @Test
    public void repeatedCancelShouldBeIdempotent() throws Exception {
        BackgroundProcessManager.BackgroundStartResult bgResult = processManager.startBackground(
                java.util.List.of("sleep", "60"), temporaryFolder.getRoot().toPath(), 120_000,
                "r-1", "c-1", "ws", cn.lunalhx.ai.domain.tool.model.BackgroundLaunchMode.EXPLICIT);
        assertTrue(bgResult.started());
        String taskId = bgResult.task().getTaskId();

        BackgroundTaskCancelService.CancelResult result1 = cancelService.cancel("r-1", taskId);
        assertTrue(result1.success());
        assertEquals(BackgroundTaskStatus.CANCELLED, result1.status());

        BackgroundTaskCancelService.CancelResult result2 = cancelService.cancel("r-1", taskId);
        assertTrue(result2.success());
        assertEquals(BackgroundTaskStatus.CANCELLED, result2.status());
    }
}
