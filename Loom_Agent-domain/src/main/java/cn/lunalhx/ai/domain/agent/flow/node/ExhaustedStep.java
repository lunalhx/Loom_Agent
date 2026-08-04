package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Set;

final class ExhaustedStep implements ContextRecoveryStep {

    private static final Set<ContextRecoveryStage> ACCEPTABLE_STATES =
            Set.of(ContextRecoveryStage.NONE, ContextRecoveryStage.REACTIVE_COMPACTED,
                    ContextRecoveryStage.FALLBACK_MODEL_SELECTED, ContextRecoveryStage.DEEP_SUMMARY_APPLIED,
                    ContextRecoveryStage.WAITING_USER_INPUT);

    @Override
    public ContextRecoveryTransition apply(ContextRecoveryRequest request, List<AgentEvent> accumulatedEvents) {
        AgentContext context = request.context();

        if (context.getParentRunId() == null) {
            context.setContextRecoveryStage(ContextRecoveryStage.WAITING_USER_INPUT);
            context.setContextBlockedReason("context_overflow: automatic recovery exhausted"
                    + (StringUtils.isBlank(context.getContextTranscriptArtifactId())
                    ? "" : ", transcriptArtifactId=" + context.getContextTranscriptArtifactId()));
            return ContextRecoveryTransition.waitUserInput(accumulatedEvents);
        }

        context.runtime().fail(AgentStopReason.CONTEXT_OVERFLOW,
                ModelErrorCode.CONTEXT_OVERFLOW.code(),
                "模型上下文超限，自动压缩、模型回退和深度摘要均未能恢复");
        return ContextRecoveryTransition.failContextOverflow(accumulatedEvents);
    }
}
