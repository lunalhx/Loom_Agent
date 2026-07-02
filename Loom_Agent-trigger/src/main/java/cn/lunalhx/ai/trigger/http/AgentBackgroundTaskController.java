package cn.lunalhx.ai.trigger.http;

import cn.lunalhx.ai.api.dto.BackgroundTaskDetailResponse;
import cn.lunalhx.ai.api.dto.BackgroundTaskResponse;
import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.trigger.http.agent.AgentBackgroundTaskHttpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/agent/code")
public class AgentBackgroundTaskController {

    private final AgentBackgroundTaskHttpService backgroundTaskHttpService;

    @GetMapping("/runs/{runId}/background-tasks")
    public Response<List<BackgroundTaskResponse>> listBackgroundTasks(@PathVariable String runId) {
        return Response.success(backgroundTaskHttpService.listTasks(runId));
    }

    @GetMapping("/runs/{runId}/background-tasks/{taskId}")
    public Response<BackgroundTaskDetailResponse> getBackgroundTask(@PathVariable String runId,
                                                                       @PathVariable String taskId,
                                                                       @RequestParam(defaultValue = "0") long stdoutOffset,
                                                                       @RequestParam(defaultValue = "0") long stderrOffset,
                                                                       @RequestParam(defaultValue = "8192") int limitBytes) {
        try {
            BackgroundTaskDetailResponse detail = backgroundTaskHttpService.getTaskDetail(
                    runId, taskId, stdoutOffset, stderrOffset, limitBytes);
            if (detail == null) {
                return Response.<BackgroundTaskDetailResponse>builder()
                        .code("BACKGROUND_TASK_NOT_FOUND")
                        .info("任务未找到")
                        .build();
            }
            return Response.success(detail);
        } catch (IllegalArgumentException e) {
            return Response.<BackgroundTaskDetailResponse>builder()
                    .code("INVALID_PARAMETER")
                    .info(e.getMessage())
                    .build();
        }
    }

    @PostMapping("/runs/{runId}/background-tasks/{taskId}/cancel")
    public Response<BackgroundTaskResponse> cancelBackgroundTask(@PathVariable String runId,
                                                                    @PathVariable String taskId) {
        BackgroundTaskResponse task = backgroundTaskHttpService.cancelTask(runId, taskId);
        if (task == null) {
            return Response.<BackgroundTaskResponse>builder()
                    .code("BACKGROUND_TASK_NOT_FOUND")
                    .info("任务未找到")
                    .build();
        }
        if ("CANCEL_FAILED".equals(task.getStatus())) {
            return Response.<BackgroundTaskResponse>builder()
                    .code("BACKGROUND_TASK_CANCEL_FAILED")
                    .info("无法取消任务")
                    .data(task)
                    .build();
        }
        return Response.success(task);
    }
}
