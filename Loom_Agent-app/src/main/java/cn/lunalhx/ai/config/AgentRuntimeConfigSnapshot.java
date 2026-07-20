package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;

import java.time.Instant;

public record AgentRuntimeConfigSnapshot(long version,
                                         String fingerprint,
                                         Instant loadedAt,
                                         AgentRuntimeProperties agent,
                                         ModelRuntimeProperties model) {
}
