package cn.lunalhx.ai.infrastructure.dao;

import cn.lunalhx.ai.infrastructure.dao.po.AgentTraceEventPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AgentTraceEventDao {

    Long insertNext(AgentTraceEventPO event);

    List<AgentTraceEventPO> selectByRunId(String runId);

    List<AgentTraceEventPO> selectByTraceId(String traceId);

    int deleteByRunIds(@Param("runIds") List<String> runIds);

    int deleteByRootRunIds(@Param("rootRunIds") List<String> rootRunIds);
}
