package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryInitializer;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

public class FinalAnswerNode extends AbstractAgentNode {

    private final ConversationHistoryAppendService ledgerAppendService;

    public FinalAnswerNode() {
        this(null);
    }

    public FinalAnswerNode(ConversationHistoryAppendService ledgerAppendService) {
        super(AgentNodeNames.FINAL_ANSWER, List.of("decision.answer", "step"));
        this.ledgerAppendService = ledgerAppendService;
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        String answer = StringUtils.defaultIfBlank(context.getDecision().getAnswer(), "未能生成最终回答");
        context.setFinalAnswer(answer);
        context.setStopReason(AgentStopReason.FINAL_ANSWER);
        if (ledgerAppendService != null) {
            ledgerAppendService.appendSystemNote(context, answer,
                    ConversationHistoryInitializer.eventKey(context.getRunId(),
                            String.valueOf(context.getStep()), "final_answer"));
        }
        return NodeResult.terminal(List.of(
                event(context, AgentEventType.ANSWER).answer(answer).build(),
                event(context, AgentEventType.DONE)
                        .stopReason(AgentStopReason.FINAL_ANSWER)
                        .stepCount(context.getStep())
                        .build()));
    }

}
