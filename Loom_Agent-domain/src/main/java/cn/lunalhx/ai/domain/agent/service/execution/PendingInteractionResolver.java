package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.PendingInteraction;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextOverflowStage;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryInitializer;
import cn.lunalhx.ai.domain.agent.service.ledger.ControlUpdateTexts;
import cn.lunalhx.ai.domain.tool.model.GrantLifetime;
import cn.lunalhx.ai.domain.tool.model.PermissionAction;
import cn.lunalhx.ai.domain.tool.model.PermissionDecision;
import cn.lunalhx.ai.domain.tool.model.PermissionGrant;
import cn.lunalhx.ai.domain.tool.service.PermissionPrompt;
import cn.lunalhx.ai.domain.tool.service.ToolAuthorizationService;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * Re-presents a durable pending approval or user-input pause after Run Recovery.
 * Answering never executes the crash-era Tool Call; the Run continues by replanning.
 */
final class PendingInteractionResolver {

    private final PermissionPrompt prompt;
    private final AgentRunLifecycle lifecycle;
    private final ConversationHistoryAppendService historyAppend = new ConversationHistoryAppendService();

    PendingInteractionResolver(PermissionPrompt prompt, AgentRunLifecycle lifecycle) {
        this.prompt = prompt;
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    List<AgentEvent> resolve(AgentContext context) {
        PendingInteraction pending = context.getPendingInteraction();
        if (pending == null) {
            return List.of();
        }
        if (pending.toolApproval()) {
            return resolveApproval(context, pending);
        }
        if (pending.userInput()) {
            return resolveUserInput(context, pending);
        }
        context.setPendingInteraction(null);
        return lifecycle.clearPending(context);
    }

    private List<AgentEvent> resolveApproval(AgentContext context, PendingInteraction pending) {
        GrantLifetime lifetime = prompt == null
                ? null
                : prompt.ask(pending.toDisplay(context.getExecutionProfile()),
                new PermissionDecision(PermissionAction.ASK, "ask", List.of(), List.of(),
                        pending.isPerCallOnly()));
        boolean approved = lifetime != null
                && !(pending.isPerCallOnly() && lifetime != GrantLifetime.ONCE);
        if (approved && lifetime != GrantLifetime.ONCE && pending.hasGrantMaterial()) {
            PermissionGrant grant = pending.toGrant(context.getExecutionProfile(), lifetime);
            if (!ToolAuthorizationService.persistReusableGrant(context, grant)) {
                approved = false;
            }
        }
        historyAppend.appendSystemNote(context,
                approved
                        ? ControlUpdateTexts.renderPendingApprovalReplan(pending.getToolName())
                        : ControlUpdateTexts.renderPendingApprovalDenied(pending.getToolName()),
                ConversationHistoryInitializer.eventKey(context.getRunId(),
                        pending.getSubjectDigest(), "pending_approval"));
        abandonOriginalCall(context);
        return lifecycle.clearPending(context);
    }

    private List<AgentEvent> resolveUserInput(AgentContext context, PendingInteraction pending) {
        String answer = prompt == null ? null : prompt.askUserInput(pending.getRedactedDisplay());
        if (StringUtils.isNotBlank(answer)) {
            context.setPendingContinuation(answer);
        }
        context.setContextOverflowStage(ContextOverflowStage.NONE);
        context.setFloorRetryPending(false);
        context.setContextBlockedReason(null);
        abandonOriginalCall(context);
        return lifecycle.clearPending(context);
    }

    private static void abandonOriginalCall(AgentContext context) {
        context.setPendingInteraction(null);
        context.setDecision(null);
        context.setToolCall(null);
        context.setAuthorizedToolCall(null);
        context.setToolResult(null);
    }
}
