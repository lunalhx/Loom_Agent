package cn.lunalhx.ai.runtime.hook;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHook;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookContext;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookEvent;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunKind;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.BudgetState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@Order(400)
public class CheckpointAgentHook implements AgentHook {

    private final AgentRunRepository runRepository;
    private final AgentCheckpointRepository checkpointRepository;
    private final ObjectMapper objectMapper;

    public CheckpointAgentHook(AgentRunRepository runRepository,
                               AgentCheckpointRepository checkpointRepository,
                               ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.checkpointRepository = checkpointRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentHookResult onEvent(AgentHookEvent event, AgentHookContext hookContext) {
        if (!shouldCheckpoint(event)) {
            return AgentHookResult.proceed();
        }
        AgentContext context = hookContext.getAgentContext();
        if (context == null || StringUtils.isBlank(context.getRunId())) {
            return AgentHookResult.proceed();
        }
        String currentNode = StringUtils.defaultIfBlank(hookContext.getNextNode(), hookContext.getNode());
        context.setCurrentNode(currentNode);
        AgentContextSnapshot snapshot = AgentContextSnapshot.from(context);
        AgentCheckpoint checkpoint = checkpointRepository.save(AgentCheckpoint.builder()
                .runId(context.getRunId())
                .currentNode(currentNode)
                .contextSnapshot(snapshot)
                .plan(context.getPlan())
                .lastToolExecutionJson(toJson(hookContext))
                .reason(StringUtils.defaultIfBlank(hookContext.getReason(), event.name()))
                .build());
        context.setCheckpointVersion(checkpoint.getVersion());
        BudgetState budget = context.getBudgetState();
        saveRun(context, currentNode, budget);
        return AgentHookResult.proceed(List.of(AgentEvent.builder()
                .type(AgentEventType.CHECKPOINT_SAVED)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .node(currentNode)
                .step(context.getStep())
                .checkpointVersion(checkpoint.getVersion())
                .build()));
    }

    private boolean shouldCheckpoint(AgentHookEvent event) {
        return event == AgentHookEvent.USER_PROMPT_SUBMIT
                || event == AgentHookEvent.AFTER_NODE
                || event == AgentHookEvent.BEFORE_TOOL
                || event == AgentHookEvent.AFTER_TOOL
                || event == AgentHookEvent.AFTER_STOP;
    }

    private void saveRun(AgentContext context, String currentNode, BudgetState budget) {
        AgentRunStatus status = AgentRunStatus.RUNNING;
        if (StringUtils.isNotBlank(context.getBudgetBlockedReason())) {
            status = AgentRunStatus.BUDGET_EXCEEDED;
        } else if (context.getStopReason() != null && context.getErrorCode() == null) {
            status = AgentRunStatus.COMPLETED;
        } else if (context.getErrorCode() != null) {
            status = AgentRunStatus.FAILED;
        }
        if ("approval_gate".equals(currentNode) && context.getStopReason() == null) {
            status = AgentRunStatus.WAITING_APPROVAL;
        } else if ("user_input_gate".equals(currentNode) && context.getStopReason() == null) {
            status = AgentRunStatus.WAITING_USER_INPUT;
        }
        runRepository.save(AgentRun.builder()
                .runId(context.getRunId())
                .parentRunId(context.getParentRunId())
                .rootRunId(StringUtils.defaultIfBlank(context.getRootRunId(), context.getRunId()))
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .agentRole(context.getAgentRole())
                .runKind(StringUtils.isBlank(context.getParentRunId()) ? AgentRunKind.ROOT : AgentRunKind.CHILD)
                .depth(context.getAgentDepth())
                .childOrdinal(context.getChildOrdinal())
                .question(context.getQuestion())
                .workspace(context.getWorkspaceDisplayName())
                .status(status)
                .currentNode(currentNode)
                .step(context.getStep())
                .checkpointVersion(context.getCheckpointVersion())
                .summaryJson(context.getAgentRole() == null ? null : context.getFinalAnswer())
                .blockedReason(StringUtils.defaultIfBlank(
                        context.getBudgetBlockedReason(), context.getContextBlockedReason()))
                .usedTokens(budget.usedTokens())
                .estimatedCost(budget.estimatedCost())
                .updatedAt(Instant.now())
                .build());
    }

    private String toJson(AgentHookContext context) {
        if (context.getToolCall() == null && context.getToolResult() == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(new ToolExecutionSnapshot(context.getToolCall(), context.getToolResult()));
        } catch (Exception e) {
            return "{\"error\":\"tool_execution_snapshot_failed\"}";
        }
    }

    private record ToolExecutionSnapshot(Object call, Object result) {
    }

}
