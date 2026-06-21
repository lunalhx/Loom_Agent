package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.infrastructure.dao.AgentRunDao;
import cn.lunalhx.ai.infrastructure.dao.po.AgentRunPO;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

public class MybatisAgentRunRepository implements AgentRunRepository {

    private final AgentRunDao agentRunDao;

    public MybatisAgentRunRepository(AgentRunDao agentRunDao) {
        this.agentRunDao = agentRunDao;
    }

    @Override
    public AgentRun save(AgentRun run) {
        agentRunDao.upsert(toPo(run));
        return run;
    }

    @Override
    public Optional<AgentRun> find(String runId) {
        return Optional.ofNullable(agentRunDao.selectByRunId(runId)).map(this::toEntity);
    }

    private AgentRunPO toPo(AgentRun run) {
        AgentRunPO po = new AgentRunPO();
        po.setRunId(run.getRunId());
        po.setRequestId(run.getRequestId());
        po.setConversationId(run.getConversationId());
        po.setQuestion(run.getQuestion());
        po.setWorkspace(run.getWorkspace());
        po.setStatus(run.getStatus() == null ? null : run.getStatus().name());
        po.setCurrentNode(run.getCurrentNode());
        po.setStep(run.getStep());
        po.setCheckpointVersion(run.getCheckpointVersion());
        return po;
    }

    private AgentRun toEntity(AgentRunPO po) {
        return AgentRun.builder()
                .runId(po.getRunId())
                .requestId(po.getRequestId())
                .conversationId(po.getConversationId())
                .question(po.getQuestion())
                .workspace(po.getWorkspace())
                .status(po.getStatus() == null ? null : AgentRunStatus.valueOf(po.getStatus()))
                .currentNode(po.getCurrentNode())
                .step(po.getStep())
                .checkpointVersion(po.getCheckpointVersion())
                .createdAt(toInstant(po.getCreateTime()))
                .updatedAt(toInstant(po.getUpdateTime()))
                .build();
    }

    private Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant();
    }

}
