package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ReplanReason;
import cn.lunalhx.ai.domain.agent.service.execution.ProgressGuard;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ReplanGuardNode extends AbstractAgentNode {

    private final ProgressGuard progressGuard;

    public ReplanGuardNode(ProgressGuard progressGuard) {
        super(AgentNodeNames.REPLAN_GUARD, List.of("toolResult", "plan", "replanReason"));
        this.progressGuard = Objects.requireNonNull(progressGuard, "progressGuard must not be null");
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        ProgressGuard.ProgressResult progressResult = progressGuard.evaluate(context);
        if (progressResult == ProgressGuard.ProgressResult.TERMINATE) {
            return NodeResult.next(AgentNodeNames.FAIL, List.of());
        }
        if (progressResult == ProgressGuard.ProgressResult.REPLAN) {
            return NodeResult.next(AgentNodeNames.REPLAN, List.of());
        }
        if (context.isUnsafeResumeRequired()) {
            context.setReplanReason(ReplanReason.UNSAFE_RESUME);
            context.setReplanMessage("上次中断可能发生在写操作或测试命令附近，恢复后需要先检查当前文件和测试状态。");
            context.setUnsafeResumeRequired(false);
            return NodeResult.next(AgentNodeNames.REPLAN, List.of());
        }
        ToolResult result = context.getToolResult();
        if (result != null && !result.isSuccess() && !isTodoWrite(context)) {
            context.setReplanReason(toReason(result));
            context.setReplanMessage(buildFailureSummary(context, result));
            return NodeResult.next(AgentNodeNames.REPLAN, List.of());
        }
        if (context.getPlan() != null) {
            context.getPlan().incrementRoundsSinceUpdate();
        }
        return NodeResult.next(AgentNodeNames.RENDER_PROMPT, List.of());
    }

    private boolean isTodoWrite(AgentContext context) {
        return context.getDecision() != null && "todo_write".equals(context.getDecision().getTool());
    }

    private ReplanReason toReason(ToolResult result) {
        if (StringUtils.containsIgnoreCase(result.getObservation(), "approval_rejected")) {
            return ReplanReason.APPROVAL_REJECTED;
        }
        if (StringUtils.containsIgnoreCase(result.getObservation(), "policy_denied")) {
            return ReplanReason.POLICY_DENIED;
        }
        return ReplanReason.TOOL_FAILURE;
    }

    private String buildFailureSummary(AgentContext context, ToolResult result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("failedTool", context.getDecision() != null ? context.getDecision().getTool() : "unknown");

        String observation = StringUtils.defaultString(result.getObservation());
        String errorCode = StringUtils.defaultString(result.getErrorCode());

        boolean isShellFailure = "run_shell".equals(context.getDecision() != null
                ? context.getDecision().getTool() : "");
        boolean isVerificationCommand = isShellFailure
                && (StringUtils.contains(observation, "test")
                || StringUtils.contains(observation, "mvn")
                || StringUtils.contains(observation, "npm test")
                || StringUtils.contains(observation, "pytest")
                || StringUtils.contains(observation, "verify"));
        boolean isEnvOrPermissionIssue = StringUtils.containsIgnoreCase(observation, "permission denied")
                || StringUtils.containsIgnoreCase(observation, "command not found")
                || StringUtils.containsIgnoreCase(observation, "not found")
                || StringUtils.containsIgnoreCase(observation, "cannot access");
        boolean failureMayBeVerifierIssue = isShellFailure
                && (isVerificationCommand || isEnvOrPermissionIssue);

        summary.put("exitCode", errorCode);
        summary.put("failureMayBeVerifierIssue", failureMayBeVerifierIssue);
        summary.put("isEnvOrPermissionIssue", isEnvOrPermissionIssue);

        String nextAllowed;
        if (isVerificationCommand || isEnvOrPermissionIssue) {
            nextAllowed = "update_existing_only";
            summary.put("nextAllowedReplanOperation", nextAllowed);
            summary.put("guidance", "优先更换验证方式或说明无法验证，不要扩展任务范围或修改业务文件");
        } else {
            nextAllowed = "may_add_scoped_task";
            summary.put("nextAllowedReplanOperation", nextAllowed);
            summary.put("guidance", "可以新增目标范围明确的任务，但必须先判断失败是否指向用户目标的真实缺陷");
        }

        StringBuilder msg = new StringBuilder();
        msg.append(observation).append("\n");
        msg.append("--- 结构化的失败分析 ---\n");
        for (Map.Entry<String, Object> entry : summary.entrySet()) {
            msg.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
        }
        return msg.toString();
    }

}
