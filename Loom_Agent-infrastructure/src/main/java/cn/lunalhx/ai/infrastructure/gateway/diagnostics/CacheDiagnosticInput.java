package cn.lunalhx.ai.infrastructure.gateway.diagnostics;

/**
 * 一次诊断的输入。不持有任何网络/IO 状态，可被网关或其他诊断源构造后传入 {@link PromptCacheDiagnostics}。
 *
 * <p>previous 侧所有字段都可为 null，表示「无上一次请求」；current 侧允许 null（按空列表处理）。
 * raw payload 是序列化后的 JSON 字符串，可选提供；超过 {@link #rawPayloadHashLimit} 字节的会被截断再哈希。
 */
public final class CacheDiagnosticInput {

    private final java.util.List<CanonicalMessage> previousMessages;
    private final java.util.List<CanonicalMessage> currentMessages;
    private final String previousRawPayload;
    private final String currentRawPayload;
    private final boolean toolsIncludedInPrevious;
    private final boolean toolsIncludedInCurrent;
    private final int previewLimit;
    private final int rawPayloadHashLimit;

    private CacheDiagnosticInput(Builder b) {
        this.previousMessages = b.previousMessages;
        // currentMessages 允许 null：diagnose() 会按空列表处理，保证 null 输入的确定行为
        this.currentMessages = b.currentMessages == null ? java.util.List.of() : b.currentMessages;
        this.previousRawPayload = b.previousRawPayload;
        this.currentRawPayload = b.currentRawPayload;
        this.toolsIncludedInPrevious = b.toolsIncludedInPrevious;
        this.toolsIncludedInCurrent = b.toolsIncludedInCurrent;
        this.previewLimit = b.previewLimit > 0 ? b.previewLimit : 240;
        this.rawPayloadHashLimit = b.rawPayloadHashLimit > 0 ? b.rawPayloadHashLimit : 64 * 1024;
    }

    public java.util.List<CanonicalMessage> previousMessages() {
        return previousMessages;
    }

    public java.util.List<CanonicalMessage> currentMessages() {
        return currentMessages;
    }

    public String previousRawPayload() {
        return previousRawPayload;
    }

    public String currentRawPayload() {
        return currentRawPayload;
    }

    public boolean toolsIncludedInPrevious() {
        return toolsIncludedInPrevious;
    }

    public boolean toolsIncludedInCurrent() {
        return toolsIncludedInCurrent;
    }

    public int previewLimit() {
        return previewLimit;
    }

    public int rawPayloadHashLimit() {
        return rawPayloadHashLimit;
    }

    public boolean hasPrevious() {
        return previousMessages != null && !previousMessages.isEmpty();
    }

    public boolean hasCurrent() {
        return currentMessages != null && !currentMessages.isEmpty();
    }

    public boolean hasRawPayloadPair() {
        return previousRawPayload != null && currentRawPayload != null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private java.util.List<CanonicalMessage> previousMessages;
        private java.util.List<CanonicalMessage> currentMessages = java.util.Collections.emptyList();
        private String previousRawPayload;
        private String currentRawPayload;
        private boolean toolsIncludedInPrevious;
        private boolean toolsIncludedInCurrent;
        private int previewLimit = 240;
        private int rawPayloadHashLimit = 64 * 1024;

        public Builder previousMessages(java.util.List<CanonicalMessage> v) {
            this.previousMessages = v;
            return this;
        }

        public Builder currentMessages(java.util.List<CanonicalMessage> v) {
            this.currentMessages = v;
            return this;
        }

        public Builder previousRawPayload(String v) {
            this.previousRawPayload = v;
            return this;
        }

        public Builder currentRawPayload(String v) {
            this.currentRawPayload = v;
            return this;
        }

        public Builder toolsIncludedInPrevious(boolean v) {
            this.toolsIncludedInPrevious = v;
            return this;
        }

        public Builder toolsIncludedInCurrent(boolean v) {
            this.toolsIncludedInCurrent = v;
            return this;
        }

        public Builder previewLimit(int v) {
            this.previewLimit = v;
            return this;
        }

        public Builder rawPayloadHashLimit(int v) {
            this.rawPayloadHashLimit = v;
            return this;
        }

        public CacheDiagnosticInput build() {
            return new CacheDiagnosticInput(this);
        }
    }
}
