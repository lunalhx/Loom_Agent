package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunKind;
import cn.lunalhx.ai.domain.agent.model.valobj.MemoryStoreProperties;
import com.google.common.cache.CacheBuilder;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

public class InMemoryAgentRunRepository implements AgentRunRepository {

    private final ConcurrentMap<String, AgentRun> runs;

    public InMemoryAgentRunRepository() {
        this.runs = new ConcurrentHashMap<>();
    }

    public InMemoryAgentRunRepository(MemoryStoreProperties props) {
        this.runs = CacheBuilder.newBuilder()
                .maximumSize(props.getMaxRuns())
                .expireAfterAccess(props.getTtlSeconds(), TimeUnit.SECONDS)
                .<String, AgentRun>build()
                .asMap();
    }

    @Override
    public AgentRun save(AgentRun run) {
        Instant now = Instant.now();
        run.setUpdatedAt(now);
        if (run.getCreatedAt() == null) {
            run.setCreatedAt(now);
        }
        runs.put(run.getRunId(), run);
        return run;
    }

    @Override
    public Optional<AgentRun> find(String runId) {
        if (StringUtils.isBlank(runId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public List<AgentRun> findChildren(String parentRunId) {
        if (StringUtils.isBlank(parentRunId)) {
            return List.of();
        }
        return runs.values().stream()
                .filter(run -> parentRunId.equals(run.getParentRunId()))
                .sorted(Comparator.comparing(run -> run.getChildOrdinal() == null ? 0 : run.getChildOrdinal()))
                .toList();
    }

    @Override
    public Optional<AgentRun> findLatestRootByConversationId(String conversationId) {
        if (StringUtils.isBlank(conversationId)) {
            return Optional.empty();
        }
        return runs.values().stream()
                .filter(run -> conversationId.equals(run.getConversationId())
                        && run.getRunKind() == AgentRunKind.ROOT)
                .max(Comparator.comparing(run -> run.getUpdatedAt() != null ? run.getUpdatedAt() : run.getCreatedAt()));
    }

    @Override
    public List<AgentRun> findByConversationId(String conversationId) {
        if (StringUtils.isBlank(conversationId)) {
            return List.of();
        }
        return runs.values().stream()
                .filter(run -> conversationId.equals(run.getConversationId()))
                .sorted(Comparator.comparing(run -> run.getCreatedAt() != null ? run.getCreatedAt() : Instant.now()))
                .toList();
    }

}
