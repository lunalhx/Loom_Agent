package cn.lunalhx.ai.infrastructure.gateway.diagnostics;

import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 将 {@link PromptCacheDiagnostics} 接到 DeepSeek 网关「最终 payload 构造与发送路径」的钩子。
 *
 * <p>调用顺序：
 * <ol>
 *   <li>网关在序列化 body 之后、构造 {@code HttpRequest} 之前调用 {@link #beforeSend}；
 *       该方法只读 body 字符串并复用同一引用去做诊断 + 写状态，绝不重新序列化。</li>
 *   <li>网关收到响应（或失败）后调用 {@link #afterSend}；该方法把诊断结果连同
 *       cache usage 一起写入日志（INFO 级别）。</li>
 * </ol>
 *
 * <p>关键不变式：
 * <ul>
 *   <li><b>Authorization / API key 永远不进入诊断输入</b>：诊断只接受 body 字符串，
 *       头部的 {@code Authorization} 在网关层独立设置，不被传入；</li>
 *   <li><b>同一字符串双用</b>：body 字符串先传给 {@code beforeSend}，再原样传给
 *       {@code HttpRequest.BodyPublishers.ofString(...)}，没有第二次序列化；</li>
 *   <li><b>状态仅在启用时分配</b>：{@code properties.enabled=false} 时 {@code state} 为 null，
 *       所有方法 fast-return，不分配对象、不写日志；</li>
 *   <li><b>fallback 隔离</b>：比较键包含 model；fallback 切换到不同模型时键变化，
 *       不同模型使用独立比较序列，第二次切回原模型时新 baseline 已建立；</li>
 *   <li><b>retry 一致性</b>：同一比较键（同一 conversation + model + capability + purpose）
 *       的连续请求复用同一序列；retry 送同一 payload 时被识别为 IDENTICAL。</li>
 * </ul>
 */
@Component
public class PromptCacheDiagnosticHook {

    private static final Logger log = LoggerFactory.getLogger(PromptCacheDiagnosticHook.class);

    private final PromptCacheDiagnosticProperties properties;
    private final PromptCacheDiagnostics diagnostics;
    private final SensitiveContentRedactor redactor;
    private final CanonicalMessagesHasher hasher;
    private final Clock clock;

    /**
     * 启用时非 null；禁用时为 null 以彻底避免状态分配。
     */
    private final PromptCacheDiagnosticStateStore state;

    public PromptCacheDiagnosticHook(PromptCacheDiagnosticProperties properties) {
        this(properties,
                new PromptCacheDiagnostics(),
                SensitiveContentRedactor.create(),
                CanonicalMessagesHasher.create(),
                Clock.systemUTC());
    }

    PromptCacheDiagnosticHook(PromptCacheDiagnosticProperties properties,
                              PromptCacheDiagnostics diagnostics,
                              SensitiveContentRedactor redactor,
                              CanonicalMessagesHasher hasher,
                              Clock clock) {
        this.properties = properties != null ? properties : new PromptCacheDiagnosticProperties();
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.state = this.properties.isEnabled()
                ? new PromptCacheDiagnosticStateStore(
                        this.properties.getMaxConversationKeys(), this.properties.getEntryTtlSeconds())
                : null;
    }

    public boolean enabled() {
        return state != null;
    }

    /**
     * 取出当前状态的容量（仅测试用）。
     */
    int stateSize() {
        return state == null ? 0 : state.size();
    }

    /**
     * 记录一次即将发送的请求的诊断「前置」步骤：
     * <ol>
     *   <li>从 state 中取出上一条 baseline（可能为 null）；</li>
     *   <li>用 {@link PromptCacheDiagnostics} 跑分类 / 哈希 / LCP / 首个差异位置；</li>
     *   <li>把当前 payload + messages 写回 state（不论结果如何），
     *       这样 retry 走同一 payload 时被识别为 IDENTICAL，fallback 切到不同模型时
     *       使用独立比较序列。</li>
     * </ol>
     *
     * <p>禁用时返回 null，调用方应原样把 body 字符串传给 {@code BodyPublishers.ofString(...)}。
     *
     * @param model             实际下发到 provider 的模型名（包含 fallback 切换结果）
     * @param capability        调用能力（{@code stream.chat} / {@code complete.agent_decision}）
     * @param purpose           调用目的（{@code FINAL_TEXT} / {@code CONTROL_JSON} / ...）
     * @param conversationId    对话 id；为 null 时按 {@code "none"} 归并
     * @param rawPayload        序列化后的 JSON 字符串；与给 {@code HttpRequest.BodyPublishers.ofString}
     *                          的字符串必须是同一引用（验证不变量：单次序列化）
     * @param currentMessages   实际进入 body 的 messages 列表（已应用 JSON 输出约束的 system 增强等变换）
     * @return 携带诊断结果的上下文；禁用时返回 null
     */
    public PromptCacheDiagnosticContext beforeSend(String model,
                                                   String capability,
                                                   String purpose,
                                                   String conversationId,
                                                   String rawPayload,
                                                   List<Map<String, String>> currentMessages) {
        if (state == null) {
            return null;
        }
        if (rawPayload == null) {
            // 序列化失败 / 空 body：诊断无意义，跳过且不写状态
            return null;
        }

        long now = clock.millis();
        PromptCacheDiagnosticLineageKey key = new PromptCacheDiagnosticLineageKey(
                model, capability, purpose, conversationId);

        PromptCacheDiagnosticStateStore.Entry previous = state.take(key, now);
        List<CanonicalMessage> canonicalCurrent = toCanonical(currentMessages);

        // 不论结果如何都先写回 state：retry 走同一 payload 时被识别为 IDENTICAL；
        // 后置的 afterSend 失败 / 网关中途崩溃也不会影响下一轮 baseline。
        state.put(key, new PromptCacheDiagnosticStateStore.Entry(rawPayload, canonicalCurrent, now));

        CacheDiagnosticInput input = CacheDiagnosticInput.builder()
                .previousMessages(previous == null ? null : previous.messages)
                .currentMessages(canonicalCurrent)
                .previousRawPayload(previous == null ? null : previous.payload)
                .currentRawPayload(rawPayload)
                // 当前网关不向 body 注入 tools 字段；这里固定 false，与 PromptCacheDiagnostics
                // 对 TOOLS_CHANGED 的判定保持一致。如果未来引入 tools 注入，这里需要从 prompt 推导。
                .toolsIncludedInPrevious(false)
                .toolsIncludedInCurrent(false)
                .build();

        CacheDiagnosticResult result = diagnostics.diagnose(input);
        return new PromptCacheDiagnosticContext(key, result, rawPayload, currentMessages);
    }

    /**
     * 把诊断结果连同 cache usage 一起写入日志。{@code context} 为 null 时（即诊断禁用时）直接返回。
     *
     * <p>默认日志字段（与规范一致）：
     * <ul>
     *   <li>raw payload hash（current + previous）；</li>
     *   <li>payload 长度（currentMessageCount 与 previousMessageCount 也带出 message count）；</li>
     *   <li>raw payload LCP；</li>
     *   <li>canonical LCP length / 首个差异 message 下标 / role；</li>
     *   <li>分类（FIRST_REQUEST / IDENTICAL / APPEND_ONLY_OK / ...）；</li>
     *   <li>cache usage（hit / miss tokens；缺失时为 null）；</li>
     *   <li>当前开关：logRedactedBody 是否开启（仅在开启时才追加 body 行）。</li>
     * </ul>
     *
     * <p>不写入：完整 payload、Authorization header、API key、原始 messages 内容。
     */
    public void afterSend(PromptCacheDiagnosticContext context, TokenUsage usage) {
        if (context == null || state == null) {
            return;
        }
        CacheDiagnosticResult result = context.result();
        PromptCacheDiagnosticLineageKey key = context.lineageKey();
        // 注意：result 内部只输出分类与计数（toString 已约束），这里打印结构化字段也只用
        // 不可逆的 hash / count / LCP，不触及任何可能含敏感信息的 content / preview。
        log.info("prompt-cache-diagnostic "
                        + "key={} model={} capability={} purpose={} conversationId={} "
                        + "category={} "
                        + "currentHash={} previousHash={} "
                        + "currentMessageCount={} previousMessageCount={} "
                        + "lcpLength={} firstDiffIndex={} firstDiffRole={} "
                        + "rawCurrentHash={} rawPreviousHash={} rawLcpLength={} "
                        + "cacheHitTokens={} cacheMissTokens={} "
                        + "logRedactedBody={}",
                key, key.model(), key.capability(), key.purpose(), key.conversationId(),
                result.category(),
                result.currentHash(), result.previousHash(),
                result.currentMessageCount(), result.previousMessageCount(),
                result.lcpLength(), result.firstDiffIndex(), result.firstDiffRole(),
                result.currentRawPayloadHash(), result.previousRawPayloadHash(), result.rawPayloadLcpLength(),
                usage == null ? null : usage.getPromptCacheHitTokens(),
                usage == null ? null : usage.getPromptCacheMissTokens(),
                properties.isLogRedactedBody());

        if (properties.isLogRedactedBody()) {
            // 1) 先脱敏（覆盖 Bearer / api-key / token / secret / password / env 等模式）
            String redacted = redactor.redact(context.rawPayload());
            // 2) 再截断到配置上限（redact 后的长度可能仍很大）
            String bounded = SensitiveContentRedactor.truncate(redacted, properties.getBodyPreviewLimit());
            log.info("prompt-cache-diagnostic-body "
                            + "key={} sensitiveRedacted=true length={} body={}",
                    key, bounded.length(), bounded);
        }
    }

    private static List<CanonicalMessage> toCanonical(List<Map<String, String>> messageMaps) {
        if (messageMaps == null || messageMaps.isEmpty()) {
            return List.of();
        }
        List<CanonicalMessage> result = new ArrayList<>(messageMaps.size());
        for (Map<String, String> m : messageMaps) {
            String role = m == null ? null : m.get("role");
            String content = m == null ? null : m.get("content");
            result.add(CanonicalMessage.of(role, content));
        }
        return result;
    }
}
