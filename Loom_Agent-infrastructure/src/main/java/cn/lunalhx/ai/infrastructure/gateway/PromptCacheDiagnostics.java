package cn.lunalhx.ai.infrastructure.gateway;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatMessage;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.valobj.PromptCacheRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 缓存诊断：只输出脱敏的结构摘要与长度，绝不把用户 prompt 或工具输出全文
 * 写入指标/日志标签。用于对比连续调用的 payload 结构（前缀长度、各 section
 * 长度、cache key）以定位命中率问题。
 */
public final class PromptCacheDiagnostics {

    private PromptCacheDiagnostics() {
    }

    /** 低基数、脱敏的调用摘要。cache key 仅取前 12 字符，正文一律只记长度。 */
    public static Map<String, Object> summary(ChatPrompt prompt, PromptCacheRequest cacheRequest) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("systemChars", length(prompt.getSystemPrompt()));
        if (prompt.getMessages() != null) {
            int i = 0;
            for (ChatMessage message : prompt.getMessages()) {
                if (message == null || message.getContent() == null) {
                    continue;
                }
                summary.put("msg" + i + ".role", safe(message.getRole()));
                summary.put("msg" + i + ".chars", length(message.getContent()));
                i++;
            }
        }
        summary.put("messageChars", length(prompt.getMessage()));
        summary.put("model", safe(prompt.getModel()));
        if (cacheRequest != null) {
            summary.put("cacheKeyHead", cacheRequest.getCacheKey() == null
                    ? null : cacheRequest.getCacheKey().substring(0, Math.min(12, cacheRequest.getCacheKey().length())));
            summary.put("policy", cacheRequest.getPolicy() == null ? "none" : cacheRequest.getPolicy().name());
            summary.put("capability", cacheRequest.getCapability() == null
                    ? "unsupported" : cacheRequest.getCapability().name());
            summary.put("enabled", cacheRequest.enabled());
        }
        return summary;
    }

    /** 前缀差异：旧/新 fingerprint 前缀比较 + 长度变化；正文内容不输出。 */
    public static Map<String, Object> prefixDiff(String oldFingerprint, String newFingerprint,
                                                 int oldChars, int newChars) {
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("oldFingerprintHead", head(oldFingerprint));
        diff.put("newFingerprintHead", head(newFingerprint));
        diff.put("oldChars", oldChars);
        diff.put("newChars", newChars);
        diff.put("changed", oldChars != newChars
                || (oldFingerprint != null && !oldFingerprint.equals(newFingerprint)));
        return diff;
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private static String head(String fingerprint) {
        return fingerprint == null ? null
                : fingerprint.substring(0, Math.min(12, fingerprint.length()));
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }
}
