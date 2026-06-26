package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ReplanReason;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;

public class ProgressGuard {

    private final AgentRuntimeProperties.StepBudgetProperties stepBudget;

    public ProgressGuard(AgentRuntimeProperties properties) {
        this.stepBudget = properties.getStepBudget();
    }

    public ProgressResult evaluate(AgentContext context) {
        if (stepBudget == null || !Boolean.TRUE.equals(stepBudget.getContinuationEnabled())) {
            return ProgressResult.CONTINUE;
        }

        ToolResult result = context.getToolResult();
        if (result == null) {
            return ProgressResult.CONTINUE;
        }

        if (result.isSuccess() && isProgressMaking(context)) {
            resetAll(context);
            return ProgressResult.CONTINUE;
        }

        if (!result.isSuccess()) {
            return evaluateFailure(context, result);
        }

        return evaluateAction(context);
    }

    private boolean isProgressMaking(AgentContext context) {
        if (context.getDecision() == null) {
            return false;
        }
        String tool = context.getDecision().getTool();
        if ("todo_write".equals(tool)) {
            return true;
        }
        if (isWriteTool(tool)) {
            return true;
        }
        if ("run_shell".equals(tool) && context.getToolResult() != null
                && context.getToolResult().isSuccess()) {
            return true;
        }
        if (context.getPlan() != null && context.getPlan().getVersion() > 0) {
            return true;
        }
        return false;
    }

    private boolean isWriteTool(String tool) {
        return "write_to_file".equals(tool)
                || "replace_in_file".equals(tool)
                || "delete_files".equals(tool);
    }

    private ProgressResult evaluateAction(AgentContext context) {
        String fingerprint = actionFingerprint(context);
        int maxRepeats = stepBudget.getSameActionMaxRepeats() != null
                ? stepBudget.getSameActionMaxRepeats() : 2;

        if (fingerprint.equals(context.getLastActionFingerprint())) {
            int repeats = context.getSameActionRepeats() + 1;
            context.setSameActionRepeats(repeats);
            if (repeats >= maxRepeats) {
                context.setStopReason(AgentStopReason.NO_PROGRESS);
                context.setErrorCode("repeated_action");
                context.setErrorMessage("连续重复相同工具和输入 " + repeats + " 次，无进展");
                context.setReplanReason(ReplanReason.REPEATED_ACTION);
                return ProgressResult.TERMINATE;
            }
        } else {
            context.setLastActionFingerprint(fingerprint);
            context.setSameActionRepeats(1);
        }
        return ProgressResult.CONTINUE;
    }

    private ProgressResult evaluateFailure(AgentContext context, ToolResult result) {
        String fingerprint = failureFingerprint(context, result);
        int maxRepeats = stepBudget.getSameFailureMaxRepeats() != null
                ? stepBudget.getSameFailureMaxRepeats() : 2;

        if (fingerprint.equals(context.getLastFailureFingerprint())) {
            int repeats = context.getSameFailureRepeats() + 1;
            context.setSameFailureRepeats(repeats);
            if (repeats >= maxRepeats) {
                context.setStopReason(AgentStopReason.NO_PROGRESS);
                context.setErrorCode("repeated_failure");
                context.setErrorMessage("连续相同失败 " + repeats + " 次，无进展");
                context.setReplanReason(ReplanReason.REPEATED_ERROR);
                return ProgressResult.TERMINATE;
            }
        } else {
            context.setLastFailureFingerprint(fingerprint);
            context.setSameFailureRepeats(1);
        }
        return ProgressResult.CONTINUE;
    }

    private void resetAll(AgentContext context) {
        context.setLastActionFingerprint(null);
        context.setSameActionRepeats(0);
        context.setLastFailureFingerprint(null);
        context.setSameFailureRepeats(0);
        context.setNoProgressRounds(0);
    }

    private String actionFingerprint(AgentContext context) {
        if (context.getDecision() == null) {
            return "";
        }
        String tool = StringUtils.defaultString(context.getDecision().getTool());
        String input = context.getDecision().getInput() == null
                ? "" : context.getDecision().getInput().toString();
        return DigestUtils.sha256Hex((tool + "|" + normalizeInput(input)).getBytes(StandardCharsets.UTF_8));
    }

    private String failureFingerprint(AgentContext context, ToolResult result) {
        String tool = context.getDecision() != null
                ? StringUtils.defaultString(context.getDecision().getTool()) : "";
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
        TERMINATE
    }

}
