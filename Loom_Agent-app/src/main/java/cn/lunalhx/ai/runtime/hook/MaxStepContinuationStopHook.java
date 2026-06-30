package cn.lunalhx.ai.runtime.hook;

import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHook;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookAction;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookContext;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookEvent;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ReplanReason;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Order(100)
public class MaxStepContinuationStopHook implements AgentHook {

    private final AgentRuntimeProperties properties;

    public MaxStepContinuationStopHook(AgentRuntimeProperties properties) {
        this.properties = properties;
    }

    @Override
    public AgentHookResult onEvent(AgentHookEvent event, AgentHookContext context) {
        if (event != AgentHookEvent.STOP) {
            return AgentHookResult.proceed();
        }

        AgentRuntimeProperties.StepBudgetProperties stepBudget = properties.getStepBudget();
        if (stepBudget == null || !Boolean.TRUE.equals(stepBudget.getContinuationEnabled())) {
            return AgentHookResult.proceed();
        }

        AgentContext agentContext = context.getAgentContext();
        if (agentContext == null) {
            return AgentHookResult.proceed();
        }

        if (!AgentNodeNames.FAIL.equals(context.getNode())) {
            return AgentHookResult.proceed();
        }

        if (!"max_steps_segment".equals(agentContext.getErrorCode())) {
            return AgentHookResult.proceed();
        }

        int nextSegment = agentContext.getSegmentIndex() + 1;
        if (nextSegment >= agentContext.getMaxSegments()) {
            return AgentHookResult.proceed();
        }

        agentContext.setSegmentIndex(nextSegment);
        agentContext.setSegmentStartStep(agentContext.getStep());
        agentContext.setStopReason(null);
        agentContext.setErrorCode(null);
        agentContext.setErrorMessage(null);
        agentContext.setFinalAnswer(null);
        agentContext.setReplanReason(ReplanReason.STEP_BUDGET_CONTINUATION);
        agentContext.setReplanMessage("第 " + (nextSegment + 1) + "/" + agentContext.getMaxSegments()
                + " 段自动续跑，已用 " + agentContext.getStep() + "/" + agentContext.getMaxTotalSteps() + " 步");

        agentContext.getDynamicText().appendSystemNote(
                agentContext.getStep(),
                AgentNodeNames.FAIL,
                "Step Budget: 分段续跑",
                "第 " + (agentContext.getSegmentIndex() + 1) + "/" + agentContext.getMaxSegments()
                        + " 段开始，已用 " + agentContext.getStep() + "/" + agentContext.getMaxTotalSteps() + " 步");

        AgentEvent hookEvent = AgentEvent.builder()
                .type(AgentEventType.STOP_HOOK_RESULT)
                .runId(agentContext.getRunId())
                .requestId(agentContext.getRequestId())
                .conversationId(agentContext.getConversationId())
                .workspace(agentContext.getWorkspaceDisplayName())
                .node(AgentNodeNames.FAIL)
                .step(agentContext.getStep())
                .metadata(Map.of(
                        "hook", "max_step_continuation",
                        "decision", "continued",
                        "nextNode", AgentNodeNames.REPLAN,
                        "segmentIndex", agentContext.getSegmentIndex(),
                        "maxSegments", agentContext.getMaxSegments(),
                        "step", agentContext.getStep(),
                        "maxTotalSteps", agentContext.getMaxTotalSteps()))
                .build();

        return AgentHookResult.interrupt(
                AgentHookAction.continueAt(AgentNodeNames.REPLAN,
                        "max_steps_segment", true),
                List.of(hookEvent));
    }

}
