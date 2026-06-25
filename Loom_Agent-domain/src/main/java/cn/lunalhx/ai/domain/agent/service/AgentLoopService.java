package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.UserInputAction;
import reactor.core.publisher.Flux;

public interface AgentLoopService {

    Flux<AgentEvent> ask(AgentQuestion question);

    Flux<AgentEvent> resume(String approvalId, ApprovalDecision decision, String reason);

    Flux<AgentEvent> resumeRun(String runId);

    Flux<AgentEvent> resumeWithUserInput(String runId, UserInputAction action, String message);

}
