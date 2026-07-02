package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.tool.model.BackgroundTaskStatus;

public interface BackgroundTaskCancelService {

    CancelResult cancel(String runId, String taskId);

    void cancelAllForRun(String runId);

    record CancelResult(boolean success, BackgroundTaskStatus status, String errorCode) {}

}
