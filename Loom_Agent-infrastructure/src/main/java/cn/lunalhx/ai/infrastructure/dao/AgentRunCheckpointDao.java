package cn.lunalhx.ai.infrastructure.dao;

import cn.lunalhx.ai.infrastructure.dao.po.AgentRunCheckpointPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentRunCheckpointDao {

    Long selectMaxVersion(String runId);

    int insert(AgentRunCheckpointPO checkpoint);

    AgentRunCheckpointPO selectLatest(String runId);

}
