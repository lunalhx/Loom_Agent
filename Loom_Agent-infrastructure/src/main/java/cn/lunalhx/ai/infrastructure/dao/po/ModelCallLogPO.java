package cn.lunalhx.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ModelCallLogPO {

    private Long id;
    private String requestId;
    private String conversationId;
    private String provider;
    private String model;
    private String status;
    private String errorCode;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Long latencyMs;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

}
