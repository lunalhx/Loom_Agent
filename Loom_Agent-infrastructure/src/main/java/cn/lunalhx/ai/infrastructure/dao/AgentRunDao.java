package cn.lunalhx.ai.infrastructure.dao;

import cn.lunalhx.ai.infrastructure.dao.po.AgentRunPO;
import cn.lunalhx.ai.infrastructure.dao.po.ConversationSummaryPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AgentRunDao {

    int upsert(AgentRunPO run);

    AgentRunPO selectByRunId(String runId);

    List<AgentRunPO> selectByParentRunId(String parentRunId);

    AgentRunPO selectLatestRootByConversationId(String conversationId);

    List<AgentRunPO> selectByConversationId(String conversationId);

    int deleteByConversationId(String conversationId);

    List<ConversationSummaryPO> selectConversationSummaries();
}
