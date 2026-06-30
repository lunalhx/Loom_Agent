package cn.lunalhx.ai.runtime.hook;

import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHook;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookAction;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookContext;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookEvent;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Order(300)
public class PendingApprovalConsistencyStopHook implements AgentHook {

    private final ApprovalStore approvalStore;

    public PendingApprovalConsistencyStopHook(ApprovalStore approvalStore) {
        this.approvalStore = approvalStore;
    }

    @Override
    public AgentHookResult onEvent(AgentHookEvent event, AgentHookContext context) {
        if (event != AgentHookEvent.STOP) {
            return AgentHookResult.proceed();
        }

        if (!AgentNodeNames.APPROVAL_GATE.equals(context.getNode())) {
            return AgentHookResult.proceed();
        }

        AgentContext agentContext = context.getAgentContext();
        if (agentContext == null) {
            return AgentHookResult.proceed();
        }

        String pendingApprovalId = agentContext.getPendingApprovalId();
        if (StringUtils.isBlank(pendingApprovalId)) {
            return AgentHookResult.proceed();
        }

        if (approvalStore.find(pendingApprovalId).isPresent()) {
            return AgentHookResult.proceed();
        }

        agentContext.setStopReason(AgentStopReason.MODEL_ERROR);
        agentContext.setErrorCode(ModelErrorCode.APPROVAL_STATE_MISSING.code());
        agentContext.setErrorMessage("审批记录 " + pendingApprovalId + " 已不可查，无法继续等待审批");

        AgentEvent hookEvent = AgentEvent.builder()
                .type(AgentEventType.STOP_HOOK_RESULT)
                .runId(agentContext.getRunId())
                .requestId(agentContext.getRequestId())
                .conversationId(agentContext.getConversationId())
                .workspace(agentContext.getWorkspaceDisplayName())
                .node(AgentNodeNames.APPROVAL_GATE)
                .step(agentContext.getStep())
                .metadata(Map.of(
                        "hook", "pending_approval_consistency",
                        "decision", "intercepted",
                        "reason", "approval_state_missing",
                        "nextNode", AgentNodeNames.FAIL,
                        "approvalId", pendingApprovalId))
                .build();

        return AgentHookResult.interrupt(
                AgentHookAction.continueAt(AgentNodeNames.FAIL,
                        "approval_state_missing", false),
                List.of(hookEvent));
    }

}
