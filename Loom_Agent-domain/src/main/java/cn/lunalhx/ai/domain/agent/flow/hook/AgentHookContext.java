package cn.lunalhx.ai.domain.agent.flow.hook;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AgentHookContext {

    private AgentContext agentContext;
    private String node;
    private String nextNode;
    private ToolCall toolCall;
    private ToolResult toolResult;
    private String reason;

}
