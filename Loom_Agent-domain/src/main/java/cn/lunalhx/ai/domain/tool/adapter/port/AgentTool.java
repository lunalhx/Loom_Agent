package cn.lunalhx.ai.domain.tool.adapter.port;

import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.CallEffectAssessment;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.ToolCapabilityEnvelope;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;

public interface AgentTool {

    ToolSpec spec();

    default String validationSchema() {
        return spec().getInputSchema();
    }

    default CallEffectAssessment assessEffect(ToolCall call) {
        ToolCapabilityEnvelope envelope = spec().getCapabilityEnvelope();
        return envelope == null || !envelope.trusted()
                ? CallEffectAssessment.untrusted()
                : CallEffectAssessment.trusted(envelope.toEffectProfile());
    }

    default CallEffectAssessment assessEffect(ToolCall call, ExecutionProfile executionProfile) {
        return assessEffect(call);
    }

    ToolResult call(ToolCall call);

}
