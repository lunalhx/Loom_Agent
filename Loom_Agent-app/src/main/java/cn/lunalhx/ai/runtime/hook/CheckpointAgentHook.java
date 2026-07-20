package cn.lunalhx.ai.runtime.hook;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ConversationDeletionRepository;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHook;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookContext;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookEvent;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookResult;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.state.AgentIdentity;
import cn.lunalhx.ai.domain.agent.model.state.AgentRuntimeState;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunKind;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.BudgetState;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import org.apache.commons.codec.digest.DigestUtils;
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
    private final ConversationDeletionRepository deletionRepository;

    public CheckpointAgentHook(AgentRunRepository runRepository,
                               AgentCheckpointRepository checkpointRepository,
                               ObjectMapper objectMapper) {
        this(runRepository, checkpointRepository, objectMapper, null);
    }

    public CheckpointAgentHook(AgentRunRepository runRepository,
                               AgentCheckpointRepository checkpointRepository,
                               ObjectMapper objectMapper,
                               ConversationDeletionRepository deletionRepository) {
        this.runRepository = runRepository;
        this.checkpointRepository = checkpointRepository;
        this.objectMapper = objectMapper;
        this.deletionRepository = deletionRepository;
    }

    @Override
    public AgentHookResult onEvent(AgentHookEvent event, AgentHookContext hookContext) {
        if (!shouldCheckpoint(event)) {
            return AgentHookResult.proceed();
        }
        AgentContext context = hookContext.getAgentContext();
        if (context == null || StringUtils.isBlank(context.identity().runId())) {
            return AgentHookResult.proceed();
        }
        String conversationId = context.identity().conversationId();
        if (conversationId != null && deletionRepository != null) {
            java.util.Optional<cn.lunalhx.ai.domain.agent.model.entity.ConversationDeletion> deletionOpt =
                    deletionRepository.find(conversationId);
            if (deletionOpt.isPresent() && !"FAILED".equals(deletionOpt.get().getStatus())) {
                return AgentHookResult.proceed();
            }
        }
        String currentNode = StringUtils.defaultIfBlank(hookContext.getNextNode(), hookContext.getNode());
        if (event == AgentHookEvent.AFTER_TOOL) {
            // The completed result is already present in the snapshot. Resume
            // at observation so the tool is never executed a second time.
            currentNode = AgentNodeNames.OBSERVATION;
        }
        context.runtime().enterNode(currentNode);
        AgentContextSnapshot snapshot = AgentContextSnapshot.from(context);
        AgentCheckpoint checkpoint = checkpointRepository.save(AgentCheckpoint.builder()
                .runId(context.identity().runId())
                .currentNode(currentNode)
                .contextSnapshot(snapshot)
                .plan(context.action().plan())
                .lastToolExecutionJson(toJson(hookContext))
                .reason(StringUtils.defaultIfBlank(hookContext.getReason(), event.name()))
                .build());
        context.runtime().setCheckpointVersion(checkpoint.getVersion());
        BudgetState budget = context.budget().budgetState();
        saveRun(context, currentNode, budget);
        return AgentHookResult.proceed(List.of(AgentEvent.builder()
                .type(AgentEventType.CHECKPOINT_SAVED)
                .runId(context.identity().runId())
                .requestId(context.identity().requestId())
                .conversationId(context.identity().conversationId())
                .workspace(context.environment().workspaceDisplayName())
                .node(currentNode)
                .step(context.runtime().step())
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
        AgentRuntimeState runtime = context.runtime();
        AgentIdentity id = context.identity();

        AgentRunStatus status = AgentRunStatus.RUNNING;
        if (StringUtils.isNotBlank(context.budget().budgetBlockedReason())) {
            status = AgentRunStatus.BUDGET_EXCEEDED;
        } else if (runtime.stopReason() == AgentStopReason.USER_CANCELLED) {
            status = AgentRunStatus.CANCELLED;
        } else if (runtime.stopReason() != null && runtime.errorCode() == null) {
            status = AgentRunStatus.COMPLETED;
        } else if (runtime.errorCode() != null) {
            status = AgentRunStatus.FAILED;
        }
        if ("approval_gate".equals(currentNode) && runtime.stopReason() == null) {
            status = AgentRunStatus.WAITING_APPROVAL;
        } else if ("user_input_gate".equals(currentNode) && runtime.stopReason() == null) {
            status = AgentRunStatus.WAITING_USER_INPUT;
        }
        runRepository.save(AgentRun.builder()
                .runId(id.runId())
                .parentRunId(id.parentRunId())
                .rootRunId(StringUtils.defaultIfBlank(id.rootRunId(), id.runId()))
                .requestId(id.requestId())
                .conversationId(id.conversationId())
                .agentRole(id.agentRole())
                .runKind(StringUtils.isBlank(id.parentRunId()) ? AgentRunKind.ROOT : AgentRunKind.CHILD)
                .depth(id.agentDepth())
                .childOrdinal(id.childOrdinal())
                .question(context.runDefinition().question())
                .workspace(context.environment().workspaceDisplayName())
                .status(status)
                .currentNode(currentNode)
                .step(runtime.step())
                .checkpointVersion(runtime.checkpointVersion())
                .summaryJson(id.agentRole() == null ? null : runtime.finalAnswer())
                .blockedReason(StringUtils.defaultIfBlank(
                        context.budget().budgetBlockedReason(), context.recovery().contextBlockedReason()))
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
            String phase = context.getReason() != null
                    && context.getReason().startsWith("before_tool:")
                    ? "STARTED" : "COMPLETED";
            ToolCall call = context.getToolCall();
            String inputFingerprint = call == null || call.getInput() == null
                    ? null : DigestUtils.sha256Hex(call.getInput().toString());
            return objectMapper.writeValueAsString(new ToolExecutionSnapshot(
                    phase,
                    call == null ? null : call.getToolCallId(),
                    inputFingerprint,
                    call,
                    context.getToolResult()));
        } catch (Exception e) {
            return "{\"error\":\"tool_execution_snapshot_failed\"}";
        }
    }

    private record ToolExecutionSnapshot(
            String phase,
            String toolCallId,
            String inputFingerprint,
            Object call,
            Object result) {
    }
}
