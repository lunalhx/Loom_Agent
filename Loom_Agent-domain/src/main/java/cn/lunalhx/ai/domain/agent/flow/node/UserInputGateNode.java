package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;

public class UserInputGateNode extends AbstractAgentNode {

    public UserInputGateNode() {
        super(AgentNodeNames.USER_INPUT_GATE,
                List.of("contextRecoveryStage", "contextTranscriptArtifactId", "contextBlockedReason"));
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        return NodeResult.terminal(List.of(event(context, AgentEventType.USER_INPUT_REQUIRED)
                .code(ModelErrorCode.CONTEXT_OVERFLOW.code())
                .message("自动上下文恢复已耗尽。请补充更聚焦的指令后继续，或终止本次运行。")
                .metadata(Map.of(
                        "allowedActions", List.of("CONTINUE", "ABORT"),
                        "recoveryStage", context.getContextRecoveryStage().name(),
                        "transcriptArtifactId", StringUtils.defaultString(context.getContextTranscriptArtifactId()),
                        "blockedReason", StringUtils.defaultString(context.getContextBlockedReason())))
                .build()));
    }

}
