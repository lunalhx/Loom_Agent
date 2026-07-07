package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.state.AgentActionState;
import cn.lunalhx.ai.domain.agent.model.state.AgentRuntimeState;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ReplanReason;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerInitializer;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolOperation;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;

public class ProgressGuard {

    private final AgentRuntimeProperties.StepBudgetProperties stepBudget;
    private final ConversationLedgerAppendService ledgerAppendService;

    public ProgressGuard(AgentRuntimeProperties properties) {
        this(properties, null);
    }

    public ProgressGuard(AgentRuntimeProperties properties,
                         ConversationLedgerAppendService ledgerAppendService) {
        this.stepBudget = properties.getStepBudget();
        this.ledgerAppendService = ledgerAppendService;
    }

    public ProgressResult evaluate(AgentContext context) {
        if (stepBudget == null || !Boolean.TRUE.equals(stepBudget.getContinuationEnabled())) {
            return ProgressResult.CONTINUE;
        }

        ToolResult result = context.action().toolResult();
        if (result == null) {
            return ProgressResult.CONTINUE;
        }

        if (result.isSuccess() && isProgressMaking(context)) {
            resetAll(context);
            if (shouldSuggestTermination(context)) {
                if (ledgerAppendService != null) {
                    ledgerAppendService.appendSystemNote(context,
                            "所有测试已通过，且没有等待保存的文件修改。如果任务已完成，请使用 final_answer 结束任务。",
                            ConversationLedgerInitializer.eventKey(context.getRunId(),
                                    String.valueOf(context.runtime().step()), "termination_suggestion"));
                }
            }
            return ProgressResult.CONTINUE;
        }

        if (!result.isSuccess()) {
            return evaluateFailure(context, result);
        }

        return evaluateAction(context);
    }

    private boolean isProgressMaking(AgentContext context) {
        AgentActionState action = context.action();
        if (action.decision() == null) {
            return false;
        }
        String tool = action.decision().getTool();
        if ("todo_write".equals(tool)) {
            return true;
        }
        if (ToolOperation.isWorkspaceWrite(tool)) {
            return true;
        }
        if ("run_shell".equals(tool) && action.toolResult() != null
                && action.toolResult().isSuccess()) {
            return true;
        }
        if (action.plan() != null && action.plan().getVersion() > 0) {
            return true;
        }
        return false;
    }

    private ProgressResult evaluateAction(AgentContext context) {
        String fingerprint = actionFingerprint(context);
        int maxRepeats = stepBudget.getSameActionMaxRepeats() != null
                ? stepBudget.getSameActionMaxRepeats() : 2;

        AgentRuntimeState runtime = context.runtime();
        if (fingerprint.equals(runtime.lastActionFingerprint())) {
            int repeats = runtime.sameActionRepeats() + 1;
            runtime.setSameActionRepeats(repeats);
            if (repeats >= maxRepeats) {
                runtime.fail(AgentStopReason.NO_PROGRESS, "repeated_action",
                        "连续重复相同工具和输入 " + repeats + " 次，无进展");
                context.action().setReplanReason(ReplanReason.REPEATED_ACTION);
                return ProgressResult.TERMINATE;
            }
        } else {
            runtime.setLastActionFingerprint(fingerprint);
            runtime.setSameActionRepeats(1);
        }
        return ProgressResult.CONTINUE;
    }

    private ProgressResult evaluateFailure(AgentContext context, ToolResult result) {
        String fingerprint = failureFingerprint(context, result);
        int maxRepeats = stepBudget.getSameFailureMaxRepeats() != null
                ? stepBudget.getSameFailureMaxRepeats() : 2;

        AgentRuntimeState runtime = context.runtime();
        if (fingerprint.equals(runtime.lastFailureFingerprint())) {
            int repeats = runtime.sameFailureRepeats() + 1;
            runtime.setSameFailureRepeats(repeats);
            if (repeats >= maxRepeats) {
                if (!runtime.repeatedFailureReplanAttempted()) {
                    runtime.setRepeatedFailureReplanAttempted(true);
                    context.action().setReplanReason(ReplanReason.REPEATED_ERROR);
                    if (ledgerAppendService != null) {
                        ledgerAppendService.appendSystemNote(context,
                                "你已连续相同失败 " + repeats + " 次。请尝试不同的方法，或者考虑是否可以结束任务。",
                                ConversationLedgerInitializer.eventKey(context.getRunId(),
                                        String.valueOf(runtime.step()), "repeated_failure_note"));
                    }
                    return ProgressResult.REPLAN;
                }
                runtime.fail(AgentStopReason.NO_PROGRESS, "repeated_failure",
                        "连续相同失败 " + repeats + " 次，无进展（已尝试 replan）");
                context.action().setReplanReason(ReplanReason.REPEATED_ERROR);
                return ProgressResult.TERMINATE;
            }
        } else {
            runtime.setLastFailureFingerprint(fingerprint);
            runtime.setSameFailureRepeats(1);
        }
        return ProgressResult.CONTINUE;
    }

    private void resetAll(AgentContext context) {
        AgentRuntimeState runtime = context.runtime();
        runtime.setLastActionFingerprint(null);
        runtime.setSameActionRepeats(0);
        runtime.setLastFailureFingerprint(null);
        runtime.setSameFailureRepeats(0);
        runtime.setRepeatedFailureReplanAttempted(false);
        runtime.setNoProgressRounds(0);
    }

    private boolean shouldSuggestTermination(AgentContext context) {
        AgentRuntimeState runtime = context.runtime();
        if (runtime.lastTestPassed() == null || !runtime.lastTestPassed()) {
            return false;
        }
        if (runtime.changedSincePassingTest()) {
            return false;
        }
        if (runtime.lastWriteStep() <= 0) {
            return false;
        }
        return true;
    }

    private String actionFingerprint(AgentContext context) {
        if (context.action().decision() == null) {
            return "";
        }
        String tool = StringUtils.defaultString(context.action().decision().getTool());
        String input = context.action().decision().getInput() == null
                ? "" : context.action().decision().getInput().toString();
        return DigestUtils.sha256Hex((tool + "|" + normalizeInput(input)).getBytes(StandardCharsets.UTF_8));
    }

    private String failureFingerprint(AgentContext context, ToolResult result) {
        String tool = context.action().decision() != null
                ? StringUtils.defaultString(context.action().decision().getTool()) : "";
        String errorCode = StringUtils.defaultString(result.getErrorCode());
        String obsHash = DigestUtils.sha256Hex(
                StringUtils.defaultString(result.getObservation()).getBytes(StandardCharsets.UTF_8));
        return DigestUtils.sha256Hex((tool + "|" + errorCode + "|" + obsHash).getBytes(StandardCharsets.UTF_8));
    }

    private String normalizeInput(String input) {
        return input.replaceAll("\\s+", " ").trim();
    }

    public enum ProgressResult {
        CONTINUE,
        REPLAN,
        TERMINATE
    }
}
