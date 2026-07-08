package cn.lunalhx.ai.domain.agent.service.conversation;

import org.apache.commons.lang3.StringUtils;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内会话级执行互斥锁，保证同一个 conversationId 同一时间只允许一个外部 root/standalone AgentLoop 运行。
 *
 * <p>子 Agent loop 不启用该 guard，避免破坏现有子 Agent 并发能力。
 * <p>该锁为纯内存互斥锁，不持久化；并发请求直接拒绝，不做排队。
 */
public final class ConversationExecutionGuard {

    private final ConcurrentHashMap<String, String> locks = new ConcurrentHashMap<>();

    /**
     * 尝试获取锁。
     *
     * @param key 锁键（优先 conversationId，fallback runId）
     * @return 加锁成功返回 token（用于后续 release 比对），失败返回 null
     */
    public String tryAcquire(String key) {
        if (StringUtils.isBlank(key)) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        String existing = locks.putIfAbsent(key, token);
        return existing == null ? token : null;
    }

    /**
     * 释放锁，仅当 token 匹配时才会释放。
     *
     * @param key   锁键
     * @param token 加锁时返回的 token
     */
    public void release(String key, String token) {
        if (StringUtils.isBlank(key) || StringUtils.isBlank(token)) {
            return;
        }
        locks.remove(key, token);
    }

    /**
     * 根据 conversationId 和 runId 生成锁键。
     * 优先使用 conversationId，缺失时使用 runId，两者都缺失返回 null。
     */
    public static String effectiveLockKey(String conversationId, String runId) {
        if (StringUtils.isNotBlank(conversationId)) {
            return conversationId;
        }
        if (StringUtils.isNotBlank(runId)) {
            return runId;
        }
        return null;
    }
}
