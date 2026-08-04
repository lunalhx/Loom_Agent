package cn.lunalhx.ai.infrastructure.adapter.deletion;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.infrastructure.dao.AgentPendingApprovalDao;
import cn.lunalhx.ai.infrastructure.dao.AgentRunCheckpointDao;
import cn.lunalhx.ai.infrastructure.dao.AgentRunDao;
import cn.lunalhx.ai.infrastructure.dao.AgentTraceEventDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class MybatisConversationPurgeHandler implements ConversationPurgeHandler {

    private static final Logger log = LoggerFactory.getLogger(MybatisConversationPurgeHandler.class);

    private final AgentRunRepository runRepository;
    private final AgentRunDao runDao;
    private final AgentTraceEventDao traceEventDao;
    private final AgentRunCheckpointDao checkpointDao;
    private final AgentPendingApprovalDao approvalDao;

    public MybatisConversationPurgeHandler(
            AgentRunRepository runRepository,
            AgentRunDao runDao,
            AgentTraceEventDao traceEventDao,
            AgentRunCheckpointDao checkpointDao,
            AgentPendingApprovalDao approvalDao) {
        this.runRepository = runRepository;
        this.runDao = runDao;
        this.traceEventDao = traceEventDao;
        this.checkpointDao = checkpointDao;
        this.approvalDao = approvalDao;
    }

    @Override
    public void purge(String conversationId) {
        List<AgentRun> runs = runRepository.findByConversationId(conversationId);
        List<String> runIds = runs.stream().map(AgentRun::getRunId).toList();
        List<String> rootRunIds = runs.stream().map(AgentRun::getRootRunId)
                .filter(id -> id != null).distinct().toList();

        approvalDao.deleteByConversationId(conversationId);

        if (!runIds.isEmpty()) {
            checkpointDao.deleteByRunIds(runIds);
        }
        if (!runIds.isEmpty()) {
            traceEventDao.deleteByRunIds(runIds);
        }
        if (!rootRunIds.isEmpty()) {
            traceEventDao.deleteByRootRunIds(rootRunIds);
        }
        runDao.deleteByConversationId(conversationId);

        log.info("Mybatis purge completed for conversation {}", conversationId);
    }
}
