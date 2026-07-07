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
import cn.lunalhx.ai.domain.agent.model.valobj.AgentPlanItemStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ReplanReason;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerInitializer;
import cn.lunalhx.ai.domain.tool.model.ToolOperation;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
        reconcilePlanFacts(agentContext);

        // After reconciliation, re-check completeness
        if (agentContext.getPlan() == null
                || (!agentContext.getPlan().hasIncompleteItems()
                && agentContext.getPlan()
                        .unmetEditTargetCount(agentContext.getTouchedFiles()) == 0)) {
            return AgentHookResult.proceed();
        }

        // --- Determine if the remaining incomplete items are genuine blockers ---
        if (isReadOnlyOrMemoryRun(agentContext)) {
            // Read-only or memory runs: non-edit items that are pending can be skipped
            // Only block if there are unmet edit targets or incomplete edit items
            long unmetEdits = agentContext.getPlan()
                    .unmetEditTargetCount(agentContext.getTouchedFiles());
            long incompleteEdits = agentContext.getPlan().incompleteEditItemCount();
            if (unmetEdits == 0 && incompleteEdits == 0) {
                // Auto-skip remaining pending items and allow completion
                autoSkipPendingNonEditItems(agentContext);
                return AgentHookResult.proceed();
            }
        }

        // --- Check test results: if tests pass and all edit targets covered, skip bookkeeping ---
        boolean testsPassing = (agentContext.getLastTestExitCode() != null
                && agentContext.getLastTestExitCode() == 0)
                || Boolean.TRUE.equals(agentContext.getLastTestPassed());
        if (testsPassing
                && agentContext.getPlan().unmetEditTargetCount(agentContext.getTouchedFiles()) == 0
                && agentContext.getPlan().incompleteEditItemCount() == 0) {
            // Tests pass + all edit targets written → auto-complete remaining items
            autoCompleteRemainingItems(agentContext);
            return AgentHookResult.proceed();
        }

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
     * Reconcile plan item states with execution facts.
     *
     * <ul>
     *   <li>inspect items → complete if the target file(s) have been read</li>
     *   <li>edit items → complete if the target file(s) have been written</li>
     *   <li>verify items → complete if tests have passed after edits</li>
     * </ul>
     */
    private void reconcilePlanFacts(AgentContext agentContext) {
        if (agentContext.getPlan() == null || agentContext.getPlan().getItems() == null) {
            return;
        }
        Set<String> touched = normalizePaths(agentContext.getTouchedFiles());
        boolean testsPassing = Boolean.TRUE.equals(agentContext.getLastTestPassed())
                && agentContext.getLastTestStep() >= agentContext.getLastWriteStep()
                && !agentContext.isChangedSincePassingTest();
        Set<String> readFiles = agentContext.getReadFiles() != null
                ? normalizePaths(agentContext.getReadFiles()) : Set.of();

        for (AgentPlanItem item : agentContext.getPlan().getItems()) {
            if (item.getStatus() != null && item.getStatus().terminal()) {
                continue; // already terminal
            }
            String kind = StringUtils.defaultString(item.getKind(), "").toLowerCase();

            if ("inspect".equals(kind) && item.getTargets() != null) {
                boolean allRead = item.getTargets().stream()
                        .map(ToolOperation::normalizePath)
                        .allMatch(readFiles::contains);
                if (allRead && !item.getTargets().isEmpty()) {
                    item.setStatus(AgentPlanItemStatus.COMPLETED);
                    item.setEvidence("目标文件已读取");
                }
            } else if ("edit".equals(kind) && item.getTargets() != null) {
                boolean allWritten = item.getTargets().stream()
                        .map(ToolOperation::normalizePath)
                        .allMatch(touched::contains);
                if (allWritten && !item.getTargets().isEmpty()) {
                    item.setStatus(AgentPlanItemStatus.COMPLETED);
                    item.setEvidence("目标文件已修改: " + String.join(", ", item.getTargets()));
                }
            } else if ("verify".equals(kind) && testsPassing) {
                item.setStatus(AgentPlanItemStatus.COMPLETED);
                item.setEvidence("测试已通过 (exit code 0)");
            }
        }
    }

    /**
     * Determine if this run was read-only or memory-only (no code writes happened).
     */
    private boolean isReadOnlyOrMemoryRun(AgentContext agentContext) {
        Set<String> touched = agentContext.getTouchedFiles();
        return touched == null || touched.isEmpty();
    }

    /**
     * Auto-skip non-edit pending items when the run was read-only/memory-only.
     */
    private void autoSkipPendingNonEditItems(AgentContext agentContext) {
        if (agentContext.getPlan() == null) return;
        for (AgentPlanItem item : agentContext.getPlan().getItems()) {
            if (item.getStatus() != null && item.getStatus().terminal()) continue;
            String kind = StringUtils.defaultString(item.getKind(), "");
            if (!"edit".equalsIgnoreCase(kind)) {
                item.setStatus(AgentPlanItemStatus.SKIPPED);
                item.setEvidence("只读/内存阶段自动跳过");
            }
        }
    }

    /**
     * Auto-complete remaining incomplete items when tests pass and all edits are done.
     */
    private void autoCompleteRemainingItems(AgentContext agentContext) {
        if (agentContext.getPlan() == null) return;
        for (AgentPlanItem item : agentContext.getPlan().getItems()) {
            if (item.getStatus() != null && item.getStatus().terminal()) continue;
            item.setStatus(AgentPlanItemStatus.COMPLETED);
            item.setEvidence("测试通过且所有编辑目标已覆盖，自动完成");
        }
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

    private Set<String> normalizePaths(Set<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return Set.of();
        }
        return paths.stream()
                .map(ToolOperation::normalizePath)
                .collect(java.util.stream.Collectors.toSet());
    }

}
