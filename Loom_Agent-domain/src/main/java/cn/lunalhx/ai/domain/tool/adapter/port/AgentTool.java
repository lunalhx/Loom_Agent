package cn.lunalhx.ai.domain.tool.adapter.port;

import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;

public interface AgentTool {

    ToolSpec spec();

    default ToolPolicyDecision policy(ToolCall call) {
        return ToolPolicyDecision.readOnly("只读工具自动放行", spec().getName() + " " + call.getInput());
    }

    ToolResult call(ToolCall call);

}
