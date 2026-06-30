package cn.lunalhx.ai.runtime.hook;

import cn.lunalhx.ai.domain.agent.adapter.port.SubAgentControlInbox;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHook;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookAction;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookContext;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookEvent;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentControlMessage;
import cn.lunalhx.ai.domain.agent.model.valobj.SubAgentControlMessageType;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(500)
public class SubAgentGracefulStopHook implements AgentHook {

    private final SubAgentControlInbox inbox;
    private final SubAgentPartialSummaryGenerator summaryGenerator;

    public SubAgentGracefulStopHook(SubAgentControlInbox inbox,
                                     SubAgentPartialSummaryGenerator summaryGenerator) {
        this.inbox = inbox;
        this.summaryGenerator = summaryGenerator;
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
