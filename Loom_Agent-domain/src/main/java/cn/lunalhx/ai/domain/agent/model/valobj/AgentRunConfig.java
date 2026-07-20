package cn.lunalhx.ai.domain.agent.model.valobj;

import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;

import java.time.Instant;
import java.util.Objects;

public record AgentRunConfig(long version,
                             String fingerprint,
                             Instant loadedAt,
                             AgentRuntimeProperties agent,
                             ModelRuntimeProperties model) {

    public AgentRunConfig {
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        Objects.requireNonNull(loadedAt, "loadedAt must not be null");
        Objects.requireNonNull(agent, "agent must not be null");
        Objects.requireNonNull(model, "model must not be null");
    }

    public static AgentRunConfig startup(AgentRuntimeProperties agent,
                                         ModelRuntimeProperties model) {
        return new AgentRunConfig(0, "startup", Instant.EPOCH, agent, model);
    }
}
