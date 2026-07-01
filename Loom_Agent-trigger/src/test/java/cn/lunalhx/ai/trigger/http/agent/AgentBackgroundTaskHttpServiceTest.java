package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.api.dto.BackgroundTaskDetailResponse;
import cn.lunalhx.ai.api.dto.BackgroundTaskResponse;
import cn.lunalhx.ai.domain.tool.adapter.port.BackgroundShellTaskRepository;
import cn.lunalhx.ai.domain.tool.model.BackgroundLaunchMode;
import cn.lunalhx.ai.domain.tool.model.BackgroundShellTask;
import cn.lunalhx.ai.domain.tool.model.BackgroundTaskStatus;
import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AgentBackgroundTaskHttpServiceTest {

    private BackgroundShellTaskRepository taskRepository;
    private AgentBackgroundTaskHttpService service;

    @Before
    public void setUp() {
        taskRepository = mock(BackgroundShellTaskRepository.class);
        service = new AgentBackgroundTaskHttpService(taskRepository);
    }

    @Test
    public void listTasksShouldMapAllFields() {
        BackgroundShellTask task = BackgroundShellTask.builder()
                .taskId("t-1").runId("r-1").conversationId("c-1").workspace("/ws")
                .command("ls").cwd("/tmp").launchMode(BackgroundLaunchMode.EXPLICIT)
                .timeoutMs(5000L).pid(12345L).status(BackgroundTaskStatus.SUCCEEDED)
                .exitCode(0).stdoutBytes(100).stderrBytes(0)
                .startedAt(Instant.parse("2024-01-01T00:00:00Z"))
                .completedAt(Instant.parse("2024-01-01T00:01:00Z"))
                .completionNotified(true)
                .build();
        when(taskRepository.findByRunId("r-1")).thenReturn(List.of(task));

        List<BackgroundTaskResponse> result = service.listTasks("r-1");
        assertEquals(1, result.size());
        BackgroundTaskResponse r = result.get(0);
        assertEquals("t-1", r.getTaskId());
        assertEquals("r-1", r.getRunId());
        assertEquals("c-1", r.getConversationId());
        assertEquals("/ws", r.getWorkspace());
        assertEquals("ls", r.getCommand());
        assertEquals("/tmp", r.getCwd());
        assertEquals("EXPLICIT", r.getLaunchMode());
        assertEquals(5000L, r.getTimeoutMs());
        assertEquals(Long.valueOf(12345L), r.getPid());
        assertEquals("SUCCEEDED", r.getStatus());
        assertEquals(Integer.valueOf(0), r.getExitCode());
        assertEquals(100L, r.getStdoutBytes());
        assertEquals(0L, r.getStderrBytes());
        assertEquals("2024-01-01T00:00:00Z", r.getStartedAt());
        assertEquals("2024-01-01T00:01:00Z", r.getCompletedAt());
        assertTrue(r.isCompletionNotified());
    }

    @Test
    public void listTasksEmptyRunShouldReturnEmptyList() {
        when(taskRepository.findByRunId("empty")).thenReturn(List.of());
        List<BackgroundTaskResponse> result = service.listTasks("empty");
        assertTrue(result.isEmpty());
    }

    @Test
    public void getTaskDetailNotFoundShouldReturnNull() {
        when(taskRepository.find("missing")).thenReturn(Optional.empty());
        BackgroundTaskDetailResponse result = service.getTaskDetail("r-1", "missing", 0, 0, 8192);
        assertNull(result);
    }

    @Test
    public void getTaskDetailRunIdMismatchShouldReturnNull() {
        BackgroundShellTask task = BackgroundShellTask.builder()
                .taskId("t-1").runId("other-run").build();
        when(taskRepository.find("t-1")).thenReturn(Optional.of(task));
        BackgroundTaskDetailResponse result = service.getTaskDetail("r-1", "t-1", 0, 0, 8192);
        assertNull(result);
    }

    @Test
    public void cancelTaskFoundShouldReturnResponse() {
        BackgroundShellTask task = BackgroundShellTask.builder()
                .taskId("t-1").runId("r-1").status(BackgroundTaskStatus.RUNNING).build();
        when(taskRepository.find("t-1")).thenReturn(Optional.of(task));
        BackgroundTaskResponse result = service.cancelTask("r-1", "t-1");
        assertNotNull(result);
        assertEquals("t-1", result.getTaskId());
        assertEquals("r-1", result.getRunId());
        assertEquals("RUNNING", result.getStatus());
    }

    @Test
    public void cancelTaskNotFoundShouldReturnNull() {
        when(taskRepository.find("missing")).thenReturn(Optional.empty());
        BackgroundTaskResponse result = service.cancelTask("r-1", "missing");
        assertNull(result);
    }
}
