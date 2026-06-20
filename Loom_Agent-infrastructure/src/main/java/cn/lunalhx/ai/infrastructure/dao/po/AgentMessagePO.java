package cn.lunalhx.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentMessagePO {

    private Long id;
    private String messageId;
    private String conversationId;
    private String role;
    private String content;
    private String model;
    private LocalDateTime createTime;

}
