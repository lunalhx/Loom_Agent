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
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Order(150)
public class CodeChangeVerificationStopHook implements AgentHook {

    private final AgentRuntimeProperties properties;

    public CodeChangeVerificationStopHook(AgentRuntimeProperties properties) {
        this.properties = properties;
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

        String reason = context.getLastTestStep() <= 0
                ? "代码修改后尚未运行测试"
                : Boolean.FALSE.equals(context.getLastTestPassed())
                ? "最近一次测试失败"
                : "最后一次成功测试早于最近写入";
        int attempt = context.getVerificationContinuationCount();
        int max = Math.max(0, guards.getMaxVerificationContinuations() == null
                ? 2 : guards.getMaxVerificationContinuations());

        if (attempt < max) {
            context.setVerificationContinuationCount(attempt + 1);
            context.setReplanReason(ReplanReason.TOOL_FAILURE);
            context.setReplanMessage(reason + "，必须运行允许的测试并根据 test_result 修复");
            context.getDynamicText().appendSystemNote(
                    context.getStep(), AgentNodeNames.FINAL_ANSWER,
                    "Stop Hook: 验证未完成", reason);
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
