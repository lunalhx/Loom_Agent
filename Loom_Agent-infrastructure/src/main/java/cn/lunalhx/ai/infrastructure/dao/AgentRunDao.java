package cn.lunalhx.ai.infrastructure.dao;

import cn.lunalhx.ai.infrastructure.dao.po.AgentRunPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentRunDao {

    int upsert(AgentRunPO run);

    AgentRunPO selectByRunId(String runId);

}
