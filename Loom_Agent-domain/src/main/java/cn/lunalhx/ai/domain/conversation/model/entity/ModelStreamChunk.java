package cn.lunalhx.ai.domain.conversation.model.entity;

import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelStreamChunk {

    private String content;
    private String finishReason;
    private TokenUsage usage;

}
