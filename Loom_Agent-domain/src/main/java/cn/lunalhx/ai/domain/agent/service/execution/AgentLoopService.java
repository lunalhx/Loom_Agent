package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.valobj.RunExitAction;
import reactor.core.publisher.Flux;

public interface AgentLoopService {

    Flux<AgentEvent> ask(AgentQuestion question);

    /**
     * Request Suspend or Abandon for an in-flight Attempt. Stops the Attempt
     * and its process tree; the first interrupt must already have become an
     * explicit {@link RunExitAction} before this is called.
     */
    void requestRunExit(String runId, RunExitAction action);
}

