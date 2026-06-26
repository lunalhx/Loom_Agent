package cn.lunalhx.ai.infrastructure.dao;

import cn.lunalhx.ai.infrastructure.dao.po.AgentRunPO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AgentRunDao {

    int upsert(AgentRunPO run);

    AgentRunPO selectByRunId(String runId);

    List<AgentRunPO> selectByParentRunId(String parentRunId);

    AgentRunPO selectLatestRootByConversationId(String conversationId);

}
