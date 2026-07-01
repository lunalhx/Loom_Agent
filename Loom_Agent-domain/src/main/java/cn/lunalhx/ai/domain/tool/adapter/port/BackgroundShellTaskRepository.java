package cn.lunalhx.ai.domain.tool.adapter.port;

import cn.lunalhx.ai.domain.tool.model.BackgroundShellTask;

import java.util.List;
import java.util.Optional;

public interface BackgroundShellTaskRepository {

    void save(BackgroundShellTask task);

    Optional<BackgroundShellTask> find(String taskId);

    List<BackgroundShellTask> findByRunId(String runId);

    List<BackgroundShellTask> findRunningByRunId(String runId);

    List<BackgroundShellTask> findStaleRunning();

    boolean markNotified(String taskId);

    void deleteByRunId(String runId);

    void deleteByConversationId(String conversationId);

}
