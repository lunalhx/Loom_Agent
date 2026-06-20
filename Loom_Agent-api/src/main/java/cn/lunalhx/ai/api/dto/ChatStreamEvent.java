package cn.lunalhx.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatStreamEvent implements Serializable {

    private static final long serialVersionUID = -851519827589187506L;

    private String type;
    private String requestId;
    private String conversationId;
    private String model;
    private String token;
    private String finishReason;
    private TokenUsageDTO usage;
    private String code;
    private String message;

}
