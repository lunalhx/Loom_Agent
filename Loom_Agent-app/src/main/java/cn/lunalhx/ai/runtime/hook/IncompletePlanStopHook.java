package cn.lunalhx.ai.runtime.hook;

import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHook;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookAction;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookContext;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookEvent;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentPlanItem;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.PlanItemVerification;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ReplanReason;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerInitializer;
import cn.lunalhx.ai.domain.agent.service.plan.PlanReconciliation;
import cn.lunalhx.ai.domain.agent.service.plan.PlanReconciliationResult;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Order(200)
public class IncompletePlanStopHook implements AgentHook {

    private final AgentRuntimeProperties properties;
    private final ConversationLedgerAppendService ledgerAppendService;

    @Autowired
    public IncompletePlanStopHook(AgentRuntimeProperties properties,
                                   ConversationLedgerAppendService ledgerAppendService) {
        this.properties = properties;
        this.ledgerAppendService = ledgerAppendService;
    }

    public IncompletePlanStopHook(AgentRuntimeProperties properties) {
        this(properties, null);
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

        // --- Reconciliation: auto-complete plan items based on execution facts ---
        PlanReconciliationResult result = PlanReconciliation.reconcile(agentContext);

        // If no real blockers, reconciliation already resolved bookkeeping items → proceed
        if (!result.hasRealBlockers()) {
            if (result.totalResolved() == 0) {
                return AgentHookResult.proceed();
            }

            List<AgentEvent> events = new ArrayList<>();

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
                            "decision", "reconciled",
                            "changedCount", result.changedCount(),
                            "bookkeepingResolved", result.bookkeepingResolvedCount()))
                    .build();
            events.add(hookEvent);

            AgentEvent planUpdated = AgentEvent.builder()
                    .type(AgentEventType.PLAN_UPDATED)
                    .runId(agentContext.getRunId())
                    .requestId(agentContext.getRequestId())
                    .conversationId(agentContext.getConversationId())
                    .workspace(agentContext.getWorkspaceDisplayName())
                    .node(AgentNodeNames.FINAL_ANSWER)
                    .step(agentContext.getStep())
                    .metadata(Map.of(
                            "reason", "reconciliation",
                            "changedCount", result.changedCount(),
                            "bookkeepingResolved", result.bookkeepingResolvedCount()))
                    .build();
            events.add(planUpdated);

            return AgentHookResult.proceed(events);
        }

        // Real blockers remain → current continuation/failure logic
        int continuationCount = agentContext.getStopHookContinuationCount();
        int maxContinuations = Math.max(0,
                incompletePlan.getMaxContinuations() != null
                        ? incompletePlan.getMaxContinuations() : 1);

        if (continuationCount < maxContinuations) {
            agentContext.setStopHookContinuationCount(continuationCount + 1);
            agentContext.setReplanReason(ReplanReason.INCOMPLETE_PLAN);
            agentContext.setReplanMessage(buildReplanMessage(agentContext, continuationCount + 1));

            if (ledgerAppendService != null) {
                ledgerAppendService.appendSystemNote(agentContext,
                        "检测到 " + incompleteItemCount(agentContext)
                                + " 个未完成计划项，第 " + (continuationCount + 1) + " 次续跑",
                        ConversationLedgerInitializer.eventKey(agentContext.getRunId(),
                                String.valueOf(Math.max(1, agentContext.getStep())), "incomplete_plan"));
            }

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

        // Max continuations exceeded
        agentContext.setStopReason(AgentStopReason.TOOL_ERROR);
        agentContext.setErrorCode("incomplete_plan");
        agentContext.setErrorMessage("计划存在未完成项，已达到最大自动续跑次数");

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
                        "decision", "failed",
                        "reason", "max_continuations_exceeded",
                        "attempt", continuationCount + 1,
                        "maxAttempts", maxContinuations,
                        "incompleteItems", incompleteItemCount(agentContext)))
                .build();

        return AgentHookResult.interrupt(
                AgentHookAction.continueAt(AgentNodeNames.FAIL,
                        "incomplete_plan", false),
                List.of(bypassEvent));
    }

    /**
     * Build a helpful replan message that guides the model to complete remaining items.
     */
    private String buildReplanMessage(AgentContext agentContext, int attempt) {
        StringBuilder msg = new StringBuilder();
        msg.append("计划存在未完成项，Stop hook 拦截最终回答并强制继续（第 ")
                .append(attempt).append(" 次）。\n");

        if (agentContext.getPlan() != null) {
            msg.append("剩余未完成项:\n");
            for (AgentPlanItem item : agentContext.getPlan().getItems()) {
                if (item.getStatus() == null || !item.getStatus().terminal()) {
                    msg.append("  - [").append(item.getStatus() == null ? "pending" : item.getStatus().code())
                            .append("] ").append(item.getContent());
                    if (item.getVerification() != null) {
                        PlanItemVerification v = item.getVerification();
                        msg.append(" verified=").append(Boolean.TRUE.equals(v.getPassed()));
                    }
                    if (StringUtils.isNotBlank(item.getKind())) {
                        msg.append(" (kind=").append(item.getKind()).append(")");
                    }
                    msg.append("\n");
                }
            }
        }

        // Include directly-callable todo_write example
        msg.append("\n请用 todo_write 标记已完成项并继续工作。示例:\n");
        msg.append("{\"todos\":[{\"id\":\"task-N\",\"status\":\"completed\",\"evidence\":\"已完成的原因\"}]}\n");
        msg.append("或更新计划后继续执行未完成的任务。");
        return msg.toString();
    }

    private int incompleteItemCount(AgentContext context) {
        if (context.getPlan() == null) {
            return 0;
        }
        return (int) context.getPlan().getItems().stream()
                .filter(item -> item.getStatus() == null || !item.getStatus().terminal())
                .count()
                + (int) context.getPlan()
                        .unmetEditTargetCount(context.getTouchedFiles());
    }

}
