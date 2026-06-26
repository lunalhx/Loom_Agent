package cn.lunalhx.ai.domain.agent.flow.hook;

import cn.lunalhx.ai.domain.agent.adapter.port.SubAgentControlInbox;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentControlMessage;
import cn.lunalhx.ai.domain.agent.model.valobj.SubAgentControlMessageType;
import cn.lunalhx.ai.domain.agent.service.SubAgentPartialSummaryGenerator;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

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
        context.getAgentContext().setDecision(AgentDecision.builder()
                .type("final")
                .answer(partialSummary)
                .thought("sub_agent_graceful_stop")
                .build());

        inbox.clear(runId);

        return AgentHookResult.interrupt(
                AgentHookAction.continueAt(AgentNodeNames.FINAL_ANSWER, "sub_agent_graceful_stop", false));
    }
}
