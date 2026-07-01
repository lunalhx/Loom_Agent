package cn.lunalhx.ai.infrastructure.dao.po;

import lombok.Data;

@Data
public class ConversationSummaryPO {
    private String conversationId;
    private String title;
    private int runCount;
    private String workspace;
}
