package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.ReplanReason;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

public class ReplanGuardNode extends AbstractAgentNode {

    public ReplanGuardNode() {
        super(AgentNodeNames.REPLAN_GUARD, List.of("toolResult", "plan", "replanReason"));
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        if (context.isUnsafeResumeRequired()) {
            context.setReplanReason(ReplanReason.UNSAFE_RESUME);
            context.setReplanMessage("上次中断可能发生在写操作或测试命令附近，恢复后需要先检查当前文件和测试状态。");
            context.setUnsafeResumeRequired(false);
            return NodeResult.next(AgentNodeNames.REPLAN, List.of());
        }
        ToolResult result = context.getToolResult();
        if (result != null && !result.isSuccess() && !isTodoWrite(context)) {
            context.setReplanReason(toReason(result));
            context.setReplanMessage(StringUtils.defaultIfBlank(result.getObservation(), "工具执行失败，需要调整计划。"));
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

}
