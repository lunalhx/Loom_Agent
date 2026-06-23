package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRole;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunKind;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.infrastructure.dao.AgentRunDao;
import cn.lunalhx.ai.infrastructure.dao.po.AgentRunPO;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
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

    @Override
    public List<AgentRun> findChildren(String parentRunId) {
        return agentRunDao.selectByParentRunId(parentRunId).stream()
                .map(this::toEntity)
                .toList();
    }

    private AgentRunPO toPo(AgentRun run) {
        AgentRunPO po = new AgentRunPO();
        po.setRunId(run.getRunId());
        po.setParentRunId(run.getParentRunId());
        po.setRootRunId(run.getRootRunId());
        po.setRequestId(run.getRequestId());
        po.setConversationId(run.getConversationId());
        po.setAgentRole(run.getAgentRole() == null ? null : run.getAgentRole().name());
        po.setRunKind(run.getRunKind() == null ? null : run.getRunKind().name());
        po.setDepth(run.getDepth());
        po.setChildOrdinal(run.getChildOrdinal());
        po.setQuestion(run.getQuestion());
        po.setWorkspace(run.getWorkspace());
        po.setStatus(run.getStatus() == null ? null : run.getStatus().name());
        po.setCurrentNode(run.getCurrentNode());
        po.setStep(run.getStep());
        po.setCheckpointVersion(run.getCheckpointVersion());
        po.setSummaryJson(run.getSummaryJson());
        po.setBlockedReason(run.getBlockedReason());
        po.setUsedTokens(run.getUsedTokens());
        po.setEstimatedCost(run.getEstimatedCost());
        return po;
    }

    private AgentRun toEntity(AgentRunPO po) {
        return AgentRun.builder()
                .runId(po.getRunId())
                .parentRunId(po.getParentRunId())
                .rootRunId(po.getRootRunId())
                .requestId(po.getRequestId())
                .conversationId(po.getConversationId())
                .agentRole(po.getAgentRole() == null ? null : AgentRole.valueOf(po.getAgentRole()))
                .runKind(po.getRunKind() == null ? null : AgentRunKind.valueOf(po.getRunKind()))
                .depth(po.getDepth())
                .childOrdinal(po.getChildOrdinal())
                .question(po.getQuestion())
                .workspace(po.getWorkspace())
                .status(po.getStatus() == null ? null : AgentRunStatus.valueOf(po.getStatus()))
                .currentNode(po.getCurrentNode())
                .step(po.getStep())
                .checkpointVersion(po.getCheckpointVersion())
                .summaryJson(po.getSummaryJson())
                .blockedReason(po.getBlockedReason())
                .usedTokens(po.getUsedTokens())
                .estimatedCost(po.getEstimatedCost())
                .createdAt(toInstant(po.getCreateTime()))
                .updatedAt(toInstant(po.getUpdateTime()))
                .build();
    }

    private Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant();
    }

}
