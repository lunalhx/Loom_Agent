package cn.lunalhx.ai.infrastructure.dao;

import cn.lunalhx.ai.infrastructure.dao.po.AgentMemoryRevisionPO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AgentMemoryRevisionDao {

    int insert(AgentMemoryRevisionPO revision);

    List<AgentMemoryRevisionPO> selectByMemoryId(String memoryId);
}
