package cn.lunalhx.ai.infrastructure.dao;

import cn.lunalhx.ai.infrastructure.dao.po.ConversationDeletionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ConversationDeletionDao {

    int upsert(ConversationDeletionPO po);

    ConversationDeletionPO selectByConversationId(String conversationId);

    List<ConversationDeletionPO> selectPendingWork();

    int claimTask(@Param("conversationId") String conversationId,
                  @Param("lockedBy") String lockedBy,
                  @Param("lockExpiresAt") String lockExpiresAt);

    int updateStatus(@Param("conversationId") String conversationId,
                     @Param("status") String status,
                     @Param("retryCount") int retryCount,
                     @Param("lastError") String lastError);

    int markCompleted(@Param("conversationId") String conversationId);

    int resetForRetry(@Param("conversationId") String conversationId);

    int releaseLock(@Param("conversationId") String conversationId);

    List<ConversationDeletionPO> selectStaleTasks(@Param("staleThreshold") String staleThreshold);
}
