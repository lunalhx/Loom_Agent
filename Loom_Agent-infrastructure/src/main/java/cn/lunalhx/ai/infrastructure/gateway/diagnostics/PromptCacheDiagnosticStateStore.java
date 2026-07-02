package cn.lunalhx.ai.infrastructure.gateway.diagnostics;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * per-key 上一条 payload + canonical messages 的状态存储。LRU + TTL 淘汰；未启用时为空（不分配）。
 *
 * <p>并发模型：所有访问走 synchronized。状态仅在诊断启用时被触达，对主链路的 QPS
 * 没有直接影响。{@link LinkedHashMap} 使用 access-order 模式，最近被 get 的条目
 * 自动移动到队尾，超过容量时队头即为最久未访问条目。
 *
 * <p>淘汰策略：
 * <ul>
 *   <li>容量淘汰：插入新条目后超过 {@code maxConversationKeys}，移除 access-order 队头；</li>
 *   <li>TTL 淘汰：get 时若距 lastSeenMs 超过 TTL 秒数则视为过期并删除（lazy）；</li>
 *   <li>读取时不做主动 sweep，避免热路径上的锁竞争；只在 put 时做按需清理。</li>
 * </ul>
 *
 * <p>存储 {@code payload} 和 {@code messages} 两份内容是有意为之：诊断需要重新计算
 * 「previous canonical hash」和「previous preview」，从 JSON 字符串里反序列化成本
 * 比直接存 canonical message 高，状态总占用也只增加 1 个引用。
 */
final class PromptCacheDiagnosticStateStore {

    private final int maxKeys;
    private final long ttlMillis;
    private final LinkedHashMap<PromptCacheDiagnosticLineageKey, Entry> entries;

    PromptCacheDiagnosticStateStore(int maxKeys, long ttlSeconds) {
        this.maxKeys = maxKeys > 0 ? maxKeys : 1024;
        this.ttlMillis = (ttlSeconds > 0 ? ttlSeconds : 600L) * 1000L;
        // accessOrder=true：每次 get 都会把条目移到队尾，实现 LRU
        this.entries = new LinkedHashMap<>(16, 0.75f, true);
    }

    /**
     * 取出 key 对应的 previous 记录；若不存在或已过期返回 null。
     */
    synchronized Entry take(PromptCacheDiagnosticLineageKey key, long nowMillis) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (nowMillis - entry.lastSeenMillis > ttlMillis) {
            entries.remove(key);
            return null;
        }
        return entry;
    }

    /**
     * 写入或更新 key 对应的 payload + messages；插入后超容则按 LRU 淘汰最久未访问条目。
     */
    synchronized void put(PromptCacheDiagnosticLineageKey key, Entry entry) {
        entries.put(key, entry);
        evictIfOverCapacity();
    }

    /**
     * 当前条目数。仅在测试中用于断言；热路径不调用。
     */
    synchronized int size() {
        return entries.size();
    }

    /**
     * 立即清空所有状态。用于关闭 / 重新加载配置。
     */
    synchronized void clear() {
        entries.clear();
    }

    private void evictIfOverCapacity() {
        if (entries.size() <= maxKeys) {
            return;
        }
        Iterator<Map.Entry<PromptCacheDiagnosticLineageKey, Entry>> it = entries.entrySet().iterator();
        // 至少淘汰 1 条（access-order 队头 = 最久未访问）
        int toEvict = entries.size() - maxKeys;
        int evicted = 0;
        while (it.hasNext() && evicted < toEvict) {
            it.next();
            it.remove();
            evicted++;
        }
    }

    static final class Entry {
        final String payload;
        final List<CanonicalMessage> messages;
        final long lastSeenMillis;

        Entry(String payload, List<CanonicalMessage> messages, long lastSeenMillis) {
            this.payload = payload;
            this.messages = messages;
            this.lastSeenMillis = lastSeenMillis;
        }
    }
}
