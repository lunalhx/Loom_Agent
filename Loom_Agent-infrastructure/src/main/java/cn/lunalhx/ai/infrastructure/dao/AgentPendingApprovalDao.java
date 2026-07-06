package cn.lunalhx.ai.infrastructure.dao;

import cn.lunalhx.ai.infrastructure.dao.po.AgentPendingApprovalPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentPendingApprovalDao {

    int upsert(AgentPendingApprovalPO approval);

    AgentPendingApprovalPO selectByApprovalId(String approvalId);

    int markConsumed(String approvalId);

    int markDecided(
            @Param("approvalId") String approvalId,
            @Param("decision") String decision,
            @Param("decisionReason") String decisionReason);

    int markResumed(String approvalId);

    int deleteByConversationId(String conversationId);
}
