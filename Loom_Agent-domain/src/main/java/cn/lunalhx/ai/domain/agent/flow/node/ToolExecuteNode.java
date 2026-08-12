package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.service.ToolExecutor;

import java.util.List;
import java.util.Objects;

/**
 * Tool execution node ({@code tool_execute}). Only runs the accepted
 * authorized call through {@link ToolExecutor} — the single boundary where
 * raw output is sanitized before it can reach any Agent state. Tool step
 * counting happens here, exactly once per accepted call.
 *
 * <p>This node never writes prompt/history/ledger/checkpoint; the executor
 * produces one sanitized result plus metadata.
 */
public class ToolExecuteNode extends AbstractAgentNode {

    private final ToolExecutor toolExecutor;

    public ToolExecuteNode(ToolExecutor toolExecutor) {
        super(AgentNodeNames.TOOL_EXECUTE, List.of("toolCall"));
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor must not be null");
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        var authorized = context.getAuthorizedToolCall();
        String tool = authorized == null ? null : authorized.rawCall().getName();
        context.runtime().advanceToolStep(tool);
        ToolResult result = toolExecutor.execute(context, authorized);
        context.setToolResult(result);
        List<AgentEvent> events = List.of(event(context, cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType.THOUGHT)
                .thought(context.getDecision() == null ? null : context.getDecision().getReason())
                .build());
        return NodeResult.nextNode(AgentNodeNames.TOOL_OUTPUT, events);
    }
}
