package cn.lunalhx.ai.domain.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelChatResult {

    private String content;
    private String finishReason;
    private TokenUsage usage;

}
