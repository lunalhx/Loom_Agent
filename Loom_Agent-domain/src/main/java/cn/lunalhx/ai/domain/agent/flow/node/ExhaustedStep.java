package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextOverflowStage;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Set;

final class ExhaustedStep implements ContextOverflowStep {

    private static final Set<ContextOverflowStage> ACCEPTABLE_STATES =
            Set.of(ContextOverflowStage.NONE, ContextOverflowStage.REACTIVE_COMPACTED,
                    ContextOverflowStage.FALLBACK_MODEL_SELECTED, ContextOverflowStage.DEEP_SUMMARY_APPLIED,
                    ContextOverflowStage.WAITING_USER_INPUT);

    @Override
    public ContextOverflowTransition apply(ContextOverflowRequest request, List<AgentEvent> accumulatedEvents) {
        AgentContext context = request.context();

        if (context.getParentRunId() == null) {
            context.setContextOverflowStage(ContextOverflowStage.WAITING_USER_INPUT);
            context.setContextBlockedReason("context_overflow: automatic recovery exhausted"
                    + (StringUtils.isBlank(context.getContextTranscriptArtifactId())
                    ? "" : ", transcriptArtifactId=" + context.getContextTranscriptArtifactId()));
            return ContextOverflowTransition.waitUserInput(accumulatedEvents);
        }

        context.runtime().fail(AgentStopReason.CONTEXT_OVERFLOW,
                ModelErrorCode.CONTEXT_OVERFLOW.code(),
                "模型上下文超限，自动压缩、模型回退和深度摘要均未能恢复");
        return ContextOverflowTransition.failContextOverflow(accumulatedEvents);
    }
}
