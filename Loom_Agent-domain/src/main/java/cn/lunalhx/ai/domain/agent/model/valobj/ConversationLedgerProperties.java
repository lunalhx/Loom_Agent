package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.Data;

@Data
public class ConversationLedgerProperties {
    private Integer compactionHighWatermark = 200;
    private Integer compactionLowWatermark = 50;
    private Integer maxCompactionDepth = 3;
}
