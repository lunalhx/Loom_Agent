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
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ReplanReason;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerInitializer;
import cn.lunalhx.ai.domain.agent.service.plan.PlanReconciliation;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Order(150)
public class CodeChangeVerificationStopHook implements AgentHook {

    private final AgentRuntimeProperties properties;
    private final ConversationLedgerAppendService ledgerAppendService;

    @Autowired
    public CodeChangeVerificationStopHook(AgentRuntimeProperties properties,
                                           ConversationLedgerAppendService ledgerAppendService) {
        this.properties = properties;
        this.ledgerAppendService = ledgerAppendService;
    }

    public CodeChangeVerificationStopHook(AgentRuntimeProperties properties) {
        this(properties, null);
    }

    @Override
    public AgentHookResult onEvent(AgentHookEvent event, AgentHookContext hookContext) {
        if (event != AgentHookEvent.STOP
                || !AgentNodeNames.FINAL_ANSWER.equals(hookContext.getNode())) {
            return AgentHookResult.proceed();
        }
        AgentContext context = hookContext.getAgentContext();
        AgentRuntimeProperties.ExecutionGuardProperties guards =
                properties.getExecutionGuards();
        if (context == null || guards == null
                || !Boolean.TRUE.equals(guards.getVerificationAfterWrite())
                || StringUtils.isNotBlank(context.getParentRunId())
                || context.getLastWriteStep() <= 0
                || (Boolean.TRUE.equals(context.getLastTestPassed())
                && context.getLastTestStep() >= context.getLastWriteStep()
                 && !context.isChangedSincePassingTest())) {
             return AgentHookResult.proceed();
         }

         PlanReconciliation.reconcile(context);

        if (context.getPlan() != null) {
            boolean allEditDone = context.getPlan().incompleteEditItemCount() == 0
                    && context.getPlan().unmetEditTargetCount(context.getTouchedFiles()) == 0;
            boolean allInspectDone = context.getPlan().getItems().stream()
                    .filter(item -> "inspect".equalsIgnoreCase(
                            StringUtils.defaultString(item.getKind(), "")))
                    .noneMatch(item -> item.getStatus() == null
                            || !item.getStatus().terminal());
            boolean noActiveVerify = context.getPlan().activeVerifyItem() == null;

            if (allEditDone && allInspectDone && noActiveVerify) {
                return AgentHookResult.proceed();
            }

            // All edit deliverables done, only verify remains → auto-block and allow final answer
            if (allEditDone && allInspectDone) {
                cn.lunalhx.ai.domain.agent.model.entity.AgentPlanItem activeVerify =
                        context.getPlan().activeVerifyItem();
                if (activeVerify != null) {
                    context.getPlan().blockItem(activeVerify.getId(),
                            "所有编辑目标已完成，验证命令可能不适用，说明见最终回复");
                    return AgentHookResult.proceed();
                }
            }
        }

         String reason = context.getLastTestStep() <= 0
                ? "代码修改后尚未运行测试"
                : Boolean.FALSE.equals(context.getLastTestPassed())
                ? "最近一次测试失败"
                : "最后一次成功测试早于最近写入";
        String guidance = "。如果验证命令本身不适用或环境不支持，请在最终回复中说明原因，不要无限循环修文件";
        int attempt = context.getVerificationContinuationCount();
        int max = Math.max(0, guards.getMaxVerificationContinuations() == null
                ? 2 : guards.getMaxVerificationContinuations());

        if (attempt < max) {
            context.setVerificationContinuationCount(attempt + 1);
            context.setReplanReason(ReplanReason.TOOL_FAILURE);
            context.setReplanMessage(reason + "，必须运行允许的测试并根据 test_result 修复" + guidance);
            if (ledgerAppendService != null) {
                ledgerAppendService.appendSystemNote(context, reason,
                        ConversationLedgerInitializer.eventKey(context.getRunId(),
                                String.valueOf(Math.max(1, context.getStep())), "verification_required"));
            }
            return AgentHookResult.interrupt(
                    AgentHookAction.continueAt(
                            AgentNodeNames.REPLAN, "verification_required", true),
                    List.of(event(context, reason, attempt + 1, max, "continued")));
        }

        context.setStopReason(AgentStopReason.TOOL_ERROR);
        context.setErrorCode("verification_incomplete");
        context.setErrorMessage(reason + "，已达到最大自动续跑次数");
        return AgentHookResult.interrupt(
                AgentHookAction.continueAt(
                        AgentNodeNames.FAIL, "verification_incomplete", false),
                List.of(event(context, reason, attempt + 1, max, "failed")));
    }

    private AgentEvent event(AgentContext context, String reason,
                             int attempt, int max, String decision) {
        return AgentEvent.builder()
                .type(AgentEventType.STOP_HOOK_RESULT)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .node(AgentNodeNames.FINAL_ANSWER)
                .step(context.getStep())
                .metadata(Map.of(
                        "hook", "code_change_verification",
                        "decision", decision,
                        "reason", reason,
                        "attempt", attempt,
                        "maxAttempts", max,
                        "lastWriteStep", context.getLastWriteStep(),
                        "lastTestStep", context.getLastTestStep()))
                .build();
    }
}
