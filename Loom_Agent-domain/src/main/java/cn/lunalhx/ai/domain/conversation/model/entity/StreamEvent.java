package cn.lunalhx.ai.domain.conversation.model.entity;

import cn.lunalhx.ai.domain.model.valobj.StreamEventType;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamEvent {

    private StreamEventType type;
    private String requestId;
    private String conversationId;
    private String model;
    private String token;
    private String finishReason;
    private TokenUsage usage;
    private String code;
    private String message;

}
