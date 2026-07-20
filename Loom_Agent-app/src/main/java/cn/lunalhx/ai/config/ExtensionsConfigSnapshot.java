package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.tool.model.ExtensionsConfig;

import java.time.Instant;

public record ExtensionsConfigSnapshot(long version,
                                       String fingerprint,
                                       Instant loadedAt,
                                       ExtensionsConfig config) {
}
