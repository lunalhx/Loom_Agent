package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import reactor.core.publisher.Flux;

public interface AgentLoopService {

    Flux<AgentEvent> ask(AgentQuestion question);
}
