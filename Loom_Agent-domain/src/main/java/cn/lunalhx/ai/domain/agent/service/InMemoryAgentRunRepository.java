package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryAgentRunRepository implements AgentRunRepository {

    private final ConcurrentMap<String, AgentRun> runs = new ConcurrentHashMap<>();

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

}
