package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentPlan;
import cn.lunalhx.ai.infrastructure.dao.AgentRunCheckpointDao;
import cn.lunalhx.ai.infrastructure.dao.po.AgentRunCheckpointPO;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;

public class MybatisAgentCheckpointRepository implements AgentCheckpointRepository {

    private final AgentRunCheckpointDao checkpointDao;
    private final ObjectMapper objectMapper;

    public MybatisAgentCheckpointRepository(AgentRunCheckpointDao checkpointDao, ObjectMapper objectMapper) {
        this.checkpointDao = checkpointDao;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentCheckpoint save(AgentCheckpoint checkpoint) {
        checkpoint.setVersion(checkpointDao.insertNext(toPo(checkpoint)));
        return checkpoint;
    }

    @Override
    public Optional<AgentCheckpoint> latest(String runId) {
        return Optional.ofNullable(checkpointDao.selectLatest(runId)).map(this::toEntity);
    }

    private AgentRunCheckpointPO toPo(AgentCheckpoint checkpoint) {
        AgentRunCheckpointPO po = new AgentRunCheckpointPO();
        po.setRunId(checkpoint.getRunId());
        po.setVersion(checkpoint.getVersion());
        po.setCurrentNode(checkpoint.getCurrentNode());
        po.setContextJson(writeJson(checkpoint.getContextSnapshot()));
        po.setPlanJson(writeJson(checkpoint.getPlan()));
        po.setLastToolExecutionJson(checkpoint.getLastToolExecutionJson());
        po.setReason(checkpoint.getReason());
        return po;
    }

    private AgentCheckpoint toEntity(AgentRunCheckpointPO po) {
        return AgentCheckpoint.builder()
                .runId(po.getRunId())
                .version(po.getVersion())
                .currentNode(po.getCurrentNode())
                .contextSnapshot(readJson(po.getContextJson(), AgentContextSnapshot.class))
                .plan(readJson(po.getPlanJson(), AgentPlan.class))
                .lastToolExecutionJson(po.getLastToolExecutionJson())
                .reason(po.getReason())
                .createdAt(po.getCreateTime())
                .build();
    }

    private String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 Agent checkpoint 失败", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return json == null ? null : objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("反序列化 Agent checkpoint 失败", e);
        }
    }

}
