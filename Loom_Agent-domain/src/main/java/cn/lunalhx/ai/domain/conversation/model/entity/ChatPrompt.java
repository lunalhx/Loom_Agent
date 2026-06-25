package cn.lunalhx.ai.domain.conversation.model.entity;

import cn.lunalhx.ai.domain.model.valobj.OutputFormat;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatPrompt {

    private String requestId;
    private String conversationId;
    private String message;
    private String systemPrompt;
    private String model;
    private Double temperature;
    private Integer maxTokens;
    private OutputFormat outputFormat;
    private String capability;
    private ModelCallPurpose purpose;
    private Long deadlineEpochMs;
    private List<ChatMessage> messages;

}
