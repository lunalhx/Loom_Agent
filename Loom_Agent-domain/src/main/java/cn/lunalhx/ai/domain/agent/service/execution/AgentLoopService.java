package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.UserInputAction;
import reactor.core.publisher.Flux;

import java.util.List;

public interface AgentLoopService {

    Flux<AgentEvent> ask(AgentQuestion question);

    Flux<AgentEvent> resume(String approvalId, ApprovalDecision decision, String reason);

    default Flux<AgentEvent> resume(
            String approvalId,
            ApprovalDecision decision,
            String reason,
            String reasonCode,
            List<String> allowedAlternatives) {
        return resume(approvalId, decision, reason);
    }

    Flux<AgentEvent> resumeRun(String runId);

    Flux<AgentEvent> resumeWithUserInput(String runId, UserInputAction action, String message);

    boolean cancelRun(String runId);

    void cancelConversation(String conversationId);

    boolean hasActiveRuns(String conversationId);
}
