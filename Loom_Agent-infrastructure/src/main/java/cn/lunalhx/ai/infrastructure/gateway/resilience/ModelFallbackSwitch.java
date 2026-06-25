package cn.lunalhx.ai.infrastructure.gateway.resilience;

public record ModelFallbackSwitch(
        String fromModel,
        String toModel,
        String reason,
        int attemptNo
) {}
