package cn.lunalhx.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentConversationPO {

    private Long id;
    private String conversationId;
    private String title;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

}
