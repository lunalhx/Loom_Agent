package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.tool.adapter.port.BackgroundShellTaskRepository;
import cn.lunalhx.ai.domain.tool.model.BackgroundShellTask;
import cn.lunalhx.ai.domain.tool.model.BackgroundTaskStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryBackgroundShellTaskRepository implements BackgroundShellTaskRepository {

    private final Map<String, BackgroundShellTask> store = new ConcurrentHashMap<>();

    @Override
    public void save(BackgroundShellTask task) {
        store.put(task.getTaskId(), task);
    }

    @Override
    public Optional<BackgroundShellTask> find(String taskId) {
        return Optional.ofNullable(store.get(taskId));
    }

    @Override
    public List<BackgroundShellTask> findByRunId(String runId) {
        return store.values().stream()
                .filter(t -> runId.equals(t.getRunId()))
                .toList();
    }

    @Override
    public List<BackgroundShellTask> findRunningByRunId(String runId) {
        return store.values().stream()
                .filter(t -> runId.equals(t.getRunId()) && t.isRunning())
                .toList();
    }

    @Override
    public List<BackgroundShellTask> findStaleRunning() {
        return store.values().stream()
                .filter(t -> t.getStatus() == BackgroundTaskStatus.STARTING
                        || t.getStatus() == BackgroundTaskStatus.RUNNING)
                .toList();
    }

    @Override
    public boolean markNotified(String taskId) {
        BackgroundShellTask task = store.get(taskId);
        if (task != null && !task.isCompletionNotified()) {
            task.setCompletionNotified(true);
            return true;
        }
        return false;
    }

    @Override
    public void deleteByRunId(String runId) {
        store.values().removeIf(t -> runId.equals(t.getRunId()));
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        store.values().removeIf(t -> conversationId.equals(t.getConversationId()));
    }

}
