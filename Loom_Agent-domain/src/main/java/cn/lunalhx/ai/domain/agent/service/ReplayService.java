package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentReplayTimeline;
import reactor.core.publisher.Flux;

public interface ReplayService {

    AgentReplayTimeline replayRun(String runId, boolean includeChildren);

    Flux<Object> streamReplayRun(String runId, boolean includeChildren);

}
