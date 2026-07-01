package cn.lunalhx.ai.infrastructure.dao;

import cn.lunalhx.ai.infrastructure.dao.po.AgentRunCheckpointPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AgentRunCheckpointDao {

    Long insertNext(AgentRunCheckpointPO checkpoint);

    AgentRunCheckpointPO selectLatest(String runId);

    int deleteByRunIds(@Param("runIds") List<String> runIds);
}
