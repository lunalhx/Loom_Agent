package cn.lunalhx.ai.infrastructure.dao;

import cn.lunalhx.ai.infrastructure.dao.po.AgentContextArtifactPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AgentContextArtifactDao {

    int insert(AgentContextArtifactPO artifact);

    AgentContextArtifactPO selectByArtifactIdAndRootRunId(@Param("artifactId") String artifactId,
                                                          @Param("rootRunId") String rootRunId);

    List<AgentContextArtifactPO> selectByRootRunId(String rootRunId);

    List<AgentContextArtifactPO> searchByRootRunId(@Param("rootRunId") String rootRunId,
                                                   @Param("query") String query,
                                                   @Param("limit") int limit);

    List<AgentContextArtifactPO> selectByConversationId(String conversationId);

    int deleteByConversationId(String conversationId);
}
