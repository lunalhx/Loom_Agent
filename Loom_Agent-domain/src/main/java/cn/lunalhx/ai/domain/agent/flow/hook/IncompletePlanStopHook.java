package cn.lunalhx.ai.domain.agent.flow.hook;

import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ReplanReason;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;

public class IncompletePlanStopHook implements AgentHook {

    private final AgentRuntimeProperties properties;

    public IncompletePlanStopHook(AgentRuntimeProperties properties) {
        this.properties = properties;
    }

    @Override
    public AgentHookResult onEvent(AgentHookEvent event, AgentHookContext context) {
        if (event != AgentHookEvent.STOP) {
            return AgentHookResult.proceed();
        }

        AgentRuntimeProperties.StopHooksProperties stopHooks = properties.getStopHooks();
        if (stopHooks == null || !Boolean.TRUE.equals(stopHooks.getEnabled())) {
            return AgentHookResult.proceed();
        }

        AgentRuntimeProperties.StopHooksProperties.IncompletePlanProperties incompletePlan =
                stopHooks.getIncompletePlan();
        if (incompletePlan == null || !Boolean.TRUE.equals(incompletePlan.getEnabled())) {
            return AgentHookResult.proceed();
        }

        AgentContext agentContext = context.getAgentContext();
        if (agentContext == null) {
            return AgentHookResult.proceed();
        }

        if (!AgentNodeNames.FINAL_ANSWER.equals(context.getNode())) {
            return AgentHookResult.proceed();
        }

        if (agentContext.getStopReason() != AgentStopReason.FINAL_ANSWER) {
            return AgentHookResult.proceed();
        }

        if (Boolean.TRUE.equals(incompletePlan.getRootOnly())
                && StringUtils.isNotBlank(agentContext.getParentRunId())) {
            return AgentHookResult.proceed();
        }

        if (agentContext.getPlan() == null || !agentContext.getPlan().hasIncompleteItems()) {
            return AgentHookResult.proceed();
        }

        int continuationCount = agentContext.getStopHookContinuationCount();
        int maxContinuations = incompletePlan.getMaxContinuations() != null
                ? incompletePlan.getMaxContinuations() : 1;

        if (continuationCount < maxContinuations) {
            agentContext.setStopHookContinuationCount(continuationCount + 1);
            agentContext.setReplanReason(ReplanReason.INCOMPLETE_PLAN);
            agentContext.setReplanMessage("计划存在未完成项，Stop hook 拦截最终回答并强制继续");

            agentContext.getDynamicText().appendSystemNote(
                    agentContext.getStep(),
                    AgentNodeNames.FINAL_ANSWER,
                    "Stop Hook: 计划未完成",
                    "检测到 " + incompleteItemCount(agentContext)
                            + " 个未完成计划项，第 " + (continuationCount + 1) + " 次续跑");

            AgentEvent hookEvent = AgentEvent.builder()
                    .type(AgentEventType.STOP_HOOK_RESULT)
                    .runId(agentContext.getRunId())
                    .requestId(agentContext.getRequestId())
                    .conversationId(agentContext.getConversationId())
                    .workspace(agentContext.getWorkspaceDisplayName())
                    .node(AgentNodeNames.FINAL_ANSWER)
                    .step(agentContext.getStep())
                    .metadata(Map.of(
                            "hook", "incomplete_plan",
                            "decision", "continued",
                            "reason", "plan_has_incomplete_items",
                            "nextNode", AgentNodeNames.REPLAN,
                            "attempt", continuationCount + 1,
                            "maxAttempts", maxContinuations,
                            "incompleteItems", incompleteItemCount(agentContext)))
                    .build();

            return AgentHookResult.interrupt(
                    AgentHookAction.continueAt(AgentNodeNames.REPLAN,
                            "plan_has_incomplete_items", true),
                    List.of(hookEvent));
        }

        AgentEvent bypassEvent = AgentEvent.builder()
                .type(AgentEventType.STOP_HOOK_RESULT)
                .runId(agentContext.getRunId())
                .requestId(agentContext.getRequestId())
                .conversationId(agentContext.getConversationId())
                .workspace(agentContext.getWorkspaceDisplayName())
                .node(AgentNodeNames.FINAL_ANSWER)
                .step(agentContext.getStep())
                .metadata(Map.of(
                        "hook", "incomplete_plan",
                        "decision", "bypassed",
                        "reason", "max_continuations_exceeded",
                        "attempt", continuationCount + 1,
                        "maxAttempts", maxContinuations,
                        "incompleteItems", incompleteItemCount(agentContext)))
                .build();

        return AgentHookResult.proceed(List.of(bypassEvent));
    }

    private int incompleteItemCount(AgentContext context) {
        if (context.getPlan() == null) {
            return 0;
        }
        return (int) context.getPlan().getItems().stream()
                .filter(item -> item.getStatus() == null || !item.getStatus().terminal())
                .count();
    }

}
