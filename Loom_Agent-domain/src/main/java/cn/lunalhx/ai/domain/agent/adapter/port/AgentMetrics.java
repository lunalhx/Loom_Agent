package cn.lunalhx.ai.domain.agent.adapter.port;

import cn.lunalhx.ai.domain.model.valobj.PromptCacheStatus;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;

public interface AgentMetrics {

    void recordRun(String runKind, String status, String errorCode);

    void recordNodeDuration(String node, String status, long durationMs);

    void recordPromptInjectionDetected(String toolName, int matchCount);

    /**
     * 每次模型调用的缓存观测（低基数维度）：provider、model、capability、
     * policy、三态 hit status。token 数值只作为 count，绝不包含
     * runId/conversationId/prompt 文本/cache key。
     */
    default void recordPromptCache(String provider, String model, String capability,
                                   String policy, PromptCacheStatus status,
                                   Integer cacheReadTokens, Integer cacheCreationTokens,
                                   TokenUsage usage) {
    }
}
