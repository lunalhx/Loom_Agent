package cn.lunalhx.ai.infrastructure.dao;

import cn.lunalhx.ai.infrastructure.dao.po.AgentPendingApprovalPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentPendingApprovalDao {

    int upsert(AgentPendingApprovalPO approval);

    AgentPendingApprovalPO selectByApprovalId(String approvalId);

    int markConsumed(String approvalId);

}
