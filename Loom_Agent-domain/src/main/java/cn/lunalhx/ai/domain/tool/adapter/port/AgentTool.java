package cn.lunalhx.ai.domain.tool.adapter.port;

import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;

public interface AgentTool {

    ToolSpec spec();

    default String validationSchema() {
        return spec().getInputSchema();
    }

    default boolean isRisky() {
        return spec().isRisky();
    }

    ToolResult call(ToolCall call);

}
