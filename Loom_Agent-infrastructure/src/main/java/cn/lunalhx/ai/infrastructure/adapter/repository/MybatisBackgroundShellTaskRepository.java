package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.tool.adapter.port.BackgroundShellTaskRepository;
import cn.lunalhx.ai.domain.tool.model.BackgroundLaunchMode;
import cn.lunalhx.ai.domain.tool.model.BackgroundShellTask;
import cn.lunalhx.ai.domain.tool.model.BackgroundTaskStatus;
import cn.lunalhx.ai.infrastructure.dao.BackgroundShellTaskDao;
import cn.lunalhx.ai.infrastructure.dao.po.BackgroundShellTaskPO;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class MybatisBackgroundShellTaskRepository implements BackgroundShellTaskRepository {

    private final BackgroundShellTaskDao dao;

    public MybatisBackgroundShellTaskRepository(BackgroundShellTaskDao dao) {
        this.dao = dao;
    }

    @Override
    public void save(BackgroundShellTask task) {
        dao.upsert(toPo(task));
    }

    @Override
    public Optional<BackgroundShellTask> find(String taskId) {
        return Optional.ofNullable(dao.selectByTaskId(taskId)).map(this::toEntity);
    }

    @Override
    public List<BackgroundShellTask> findByRunId(String runId) {
        return dao.selectByRunId(runId).stream().map(this::toEntity).toList();
    }

    @Override
    public List<BackgroundShellTask> findRunningByRunId(String runId) {
        return dao.selectRunningByRunId(runId).stream().map(this::toEntity).toList();
    }

    @Override
    public List<BackgroundShellTask> findStaleRunning() {
        return dao.selectStaleRunning(List.of("STARTING", "RUNNING")).stream()
                .map(this::toEntity).toList();
    }

    @Override
    public boolean markNotified(String taskId) {
        return dao.markNotified(taskId) > 0;
    }

    @Override
    public void deleteByRunId(String runId) {
        dao.deleteByRunId(runId);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        dao.deleteByConversationId(conversationId);
    }

    private BackgroundShellTaskPO toPo(BackgroundShellTask t) {
        BackgroundShellTaskPO po = new BackgroundShellTaskPO();
        po.setTaskId(t.getTaskId());
        po.setRunId(t.getRunId());
        po.setConversationId(t.getConversationId());
        po.setWorkspace(t.getWorkspace());
        po.setCommand(t.getCommand());
        po.setCwd(t.getCwd());
        po.setLaunchMode(t.getLaunchMode() == null ? null : t.getLaunchMode().name());
        po.setTimeoutMs(t.getTimeoutMs());
        po.setPid(t.getPid());
        po.setStatus(t.getStatus() == null ? null : t.getStatus().name());
        po.setExitCode(t.getExitCode());
        po.setErrorCode(t.getErrorCode());
        po.setErrorMessage(t.getErrorMessage());
        po.setStdoutFile(t.getStdoutFile());
        po.setStderrFile(t.getStderrFile());
        po.setStdoutBytes(t.getStdoutBytes());
        po.setStderrBytes(t.getStderrBytes());
        po.setStartedAt(t.getStartedAt() == null ? null : t.getStartedAt().toString());
        po.setCompletedAt(t.getCompletedAt() == null ? null : t.getCompletedAt().toString());
        po.setCompletionNotified(t.isCompletionNotified() ? 1 : 0);
        return po;
    }

    private BackgroundShellTask toEntity(BackgroundShellTaskPO po) {
        return BackgroundShellTask.builder()
                .taskId(po.getTaskId())
                .runId(po.getRunId())
                .conversationId(po.getConversationId())
                .workspace(po.getWorkspace())
                .command(po.getCommand())
                .cwd(po.getCwd())
                .launchMode(po.getLaunchMode() == null ? null : BackgroundLaunchMode.valueOf(po.getLaunchMode()))
                .timeoutMs(po.getTimeoutMs())
                .pid(po.getPid())
                .status(po.getStatus() == null ? null : BackgroundTaskStatus.valueOf(po.getStatus()))
                .exitCode(po.getExitCode())
                .errorCode(po.getErrorCode())
                .errorMessage(po.getErrorMessage())
                .stdoutFile(po.getStdoutFile())
                .stderrFile(po.getStderrFile())
                .stdoutBytes(po.getStdoutBytes())
                .stderrBytes(po.getStderrBytes())
                .startedAt(po.getStartedAt() == null ? null : Instant.parse(po.getStartedAt()))
                .completedAt(po.getCompletedAt() == null ? null : Instant.parse(po.getCompletedAt()))
                .completionNotified(po.getCompletionNotified() != 0)
                .createdAt(po.getCreateTime() == null ? null : Instant.parse(po.getCreateTime()))
                .updatedAt(po.getUpdateTime() == null ? null : Instant.parse(po.getUpdateTime()))
                .build();
    }

}
