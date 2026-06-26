package cn.lunalhx.ai.domain.agent.flow.hook;

import cn.lunalhx.ai.domain.agent.adapter.port.SubAgentControlInbox;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentControlMessage;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.SubAgentControlMessageType;
import cn.lunalhx.ai.domain.agent.service.SubAgentPartialSummaryGenerator;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;

public class SubAgentGracefulStopHook implements AgentHook {

    private volatile SubAgentControlInbox inbox;
    private final SubAgentPartialSummaryGenerator summaryGenerator;

    public SubAgentGracefulStopHook(SubAgentPartialSummaryGenerator summaryGenerator) {
        this.summaryGenerator = summaryGenerator;
    }

    public void setInbox(SubAgentControlInbox inbox) {
        this.inbox = inbox;
    }

    @Override
    public AgentHookResult onEvent(AgentHookEvent event, AgentHookContext context) {
        if (event != AgentHookEvent.BEFORE_NODE) {
            return AgentHookResult.proceed();
        }
        if (inbox == null || context.getAgentContext() == null) {
            return AgentHookResult.proceed();
        }
        String runId = context.getAgentContext().getRunId();
        if (StringUtils.isBlank(runId)) {
            return AgentHookResult.proceed();
        }
        if (StringUtils.isBlank(context.getAgentContext().getParentRunId())) {
            return AgentHookResult.proceed();
        }

        List<SubAgentControlMessage> messages = inbox.poll(runId);
        boolean gracefulStop = messages.stream()
                .anyMatch(m -> m.getType() == SubAgentControlMessageType.GRACEFUL_STOP_REQUESTED);
        if (!gracefulStop) {
            return AgentHookResult.proceed();
        }

        String partialSummary = summaryGenerator.generate(context.getAgentContext());
        context.getAgentContext().setFinalAnswer(partialSummary);
        context.getAgentContext().setStopReason(AgentStopReason.FINAL_ANSWER);

        AgentEvent answerEvent = AgentEvent.builder()
                .type(AgentEventType.ANSWER)
                .runId(runId)
                .requestId(context.getAgentContext().getRequestId())
                .conversationId(context.getAgentContext().getConversationId())
                .workspace(context.getAgentContext().getWorkspaceDisplayName())
                .answer(partialSummary)
                .step(context.getAgentContext().getStep())
                .metadata(Map.of("partial", true))
                .build();

        return AgentHookResult.proceed(List.of(answerEvent));
    }
}
