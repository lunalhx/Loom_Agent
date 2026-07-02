package cn.lunalhx.ai.infrastructure.gateway.diagnostics;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

/**
 * Prompt 缓存诊断的配置。默认全部关闭，避免在生产环境意外开启带来额外开销与潜在敏感信息暴露。
 *
 * <p>所有键都参与 {@code loom.ai.diagnostics.prompt-cache} 前缀，与 {@code application-*.yml} 中
 * 的配置块对应。{@code enabled} 是总开关；其它键仅在 {@code enabled=true} 时生效。
 *
 * <p>设计约束：
 * <ul>
 *   <li>默认 {@code enabled=false}：所有环境（dev/prod/test）默认关闭，测试可通过显式注入 {@code true} 的实例打开；</li>
 *   <li>{@code logRedactedBody} 默认 {@code false}：开启后必须先脱敏再截断，并在日志中标记 {@code sensitiveRedacted=true}；</li>
 *   <li>{@code maxConversationKeys} / {@code entryTtlSeconds} 控制状态淘汰：超过容量按 LRU 淘汰，超时则视为无 previous；</li>
 *   <li>关闭时 state store 不分配、hook 方法不产生任何副作用（包括分配对象）。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "loom.ai.diagnostics.prompt-cache")
public class PromptCacheDiagnosticProperties {

    /** 总开关；默认关闭。 */
    private boolean enabled = false;

    /**
     * 是否记录已脱敏且截断后的 body。默认关闭；开启时必须在日志中标记 {@code sensitiveRedacted=true}，
     * 且实际写入的字符串先经过 {@link SensitiveContentRedactor} 再被截断到 {@link #bodyPreviewLimit}。
     */
    private boolean logRedactedBody = false;

    /**
     * 写入日志的 body 预览上限（字符数）。原始 payload 长度可能很大；只截取前 N 字符写入。
     * 仅在 {@link #logRedactedBody} 为 true 时生效。
     */
    private int bodyPreviewLimit = 240;

    /**
     * 比较键的最大容量。超过后按 LRU 淘汰最久未访问的 key。设小一些以限制内存占用。
     */
    private int maxConversationKeys = 1024;

    /**
     * 单条 previous 记录的有效期（秒）。超过则视为无 previous，触发 FIRST_REQUEST。
     * 用于应对：long-lived 进程 + 对话已结束，但内存中残留旧 baseline 的场景。
     */
    private long entryTtlSeconds = 600L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isLogRedactedBody() {
        return logRedactedBody;
    }

    public void setLogRedactedBody(boolean logRedactedBody) {
        this.logRedactedBody = logRedactedBody;
    }

    public int getBodyPreviewLimit() {
        return bodyPreviewLimit;
    }

    public void setBodyPreviewLimit(int bodyPreviewLimit) {
        this.bodyPreviewLimit = bodyPreviewLimit > 0 ? bodyPreviewLimit : 240;
    }

    public int getMaxConversationKeys() {
        return maxConversationKeys;
    }

    public void setMaxConversationKeys(int maxConversationKeys) {
        this.maxConversationKeys = maxConversationKeys > 0 ? maxConversationKeys : 1024;
    }

    public long getEntryTtlSeconds() {
        return entryTtlSeconds;
    }

    public void setEntryTtlSeconds(long entryTtlSeconds) {
        this.entryTtlSeconds = entryTtlSeconds > 0 ? entryTtlSeconds : 600L;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PromptCacheDiagnosticProperties that)) return false;
        return enabled == that.enabled
                && logRedactedBody == that.logRedactedBody
                && bodyPreviewLimit == that.bodyPreviewLimit
                && maxConversationKeys == that.maxConversationKeys
                && entryTtlSeconds == that.entryTtlSeconds;
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, logRedactedBody, bodyPreviewLimit, maxConversationKeys, entryTtlSeconds);
    }
}
