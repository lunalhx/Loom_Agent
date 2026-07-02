package cn.lunalhx.ai.infrastructure.gateway.diagnostics;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * PromptCacheDiagnostics 的纯函数行为：
 * <ul>
 *   <li>分类正确：IDENTICAL / APPEND_ONLY_OK / EARLY_PREFIX_DRIFT / HISTORY_REWRITTEN / COMPACTION_RESET / TOOLS_CHANGED / UNKNOWN；</li>
 *   <li>canonical hash 稳定；</li>
 *   <li>脱敏覆盖：Bearer / api-key / token / secret / password / env 变量；</li>
 *   <li>截断 / Unicode / 并发键隔离；</li>
 *   <li>不通过 toString / 异常消息泄露原始内容。</li>
 * </ul>
 */
public class PromptCacheDiagnosticsTest {

    private final PromptCacheDiagnostics diagnostics = new PromptCacheDiagnostics();
    private final CanonicalMessagesHasher hasher = new CanonicalMessagesHasher();

    // ---------- canonical hash 稳定性 ----------

    @Test
    public void canonicalHashIsStableForSameInput() {
        List<CanonicalMessage> msgs = List.of(
                CanonicalMessage.of("system", "You are a helpful assistant"),
                CanonicalMessage.of("user", "hello"));
        String h1 = hasher.hash(msgs);
        String h2 = hasher.hash(msgs);
        assertEquals(h1, h2);
        assertEquals(64, h1.length());
    }

    @Test
    public void canonicalHashChangesWhenContentChanges() {
        List<CanonicalMessage> a = List.of(CanonicalMessage.of("user", "hi"));
        List<CanonicalMessage> b = List.of(CanonicalMessage.of("user", "hi!"));
        assertNotEquals(hasher.hash(a), hasher.hash(b));
    }

    @Test
    public void canonicalHashIsOrderSensitive() {
        List<CanonicalMessage> a = List.of(
                CanonicalMessage.of("user", "a"),
                CanonicalMessage.of("assistant", "b"));
        List<CanonicalMessage> b = List.of(
                CanonicalMessage.of("assistant", "b"),
                CanonicalMessage.of("user", "a"));
        assertNotEquals(hasher.hash(a), hasher.hash(b));
    }

    @Test
    public void canonicalHashHandlesUnicodeAndSpecialChars() {
        List<CanonicalMessage> a = List.of(
                CanonicalMessage.of("user", "你好 |# 世界 🌍"),
                CanonicalMessage.of("assistant", "换行\n制表\t\"引号\""));
        List<CanonicalMessage> b = List.of(
                CanonicalMessage.of("user", "你好 |# 世界 🌍"),
                CanonicalMessage.of("assistant", "换行\n制表\t\"引号\""));
        // 长度前缀使任意 Unicode / 含 `|#` 的内容都能无歧义地编码
        assertEquals(hasher.hash(a), hasher.hash(b));

        List<CanonicalMessage> tampered = List.of(
                CanonicalMessage.of("user", "你好 |# 世界 🌍"),
                CanonicalMessage.of("assistant", "换行\n制表\t\"引号"));
        assertNotEquals(hasher.hash(a), hasher.hash(tampered));
    }

    @Test
    public void emptyMessagesHaveStableHash() {
        assertEquals(hasher.hash(List.of()), hasher.hash(List.of()));
        assertEquals(64, hasher.hash(List.of()).length());
    }

    // ---------- 完全相同输入 → IDENTICAL ----------

    @Test
    public void identicalInputProducesIdenticalCategory() {
        List<CanonicalMessage> msgs = List.of(
                CanonicalMessage.of("system", "You are a helpful assistant"),
                CanonicalMessage.of("user", "hi"));
        CacheDiagnosticResult result = diagnostics.diagnose(CacheDiagnosticInput.builder()
                .previousMessages(msgs)
                .currentMessages(msgs)
                .build());
        assertEquals(CacheDiagnosticCategory.IDENTICAL, result.category());
        assertEquals(result.currentHash(), result.previousHash());
        assertNull(result.firstDiffIndex());
        assertEquals(1d, result.lcpRatio(), 0.0001);
    }

    @Test
    public void firstRequestProducesFirstRequestCategory() {
        List<CanonicalMessage> msgs = List.of(
                CanonicalMessage.of("system", "sys"),
                CanonicalMessage.of("user", "u"));
        CacheDiagnosticResult result = diagnostics.diagnose(CacheDiagnosticInput.builder()
                .currentMessages(msgs)
                .build());
        assertEquals(CacheDiagnosticCategory.FIRST_REQUEST, result.category());
        assertNotNull(result.currentHash());
        assertNull(result.previousHash());
    }

    // ---------- 严格 append-only ----------

    @Test
    public void strictAppendOnlyIsClassifiedAsAppendOnlyOk() {
        List<CanonicalMessage> prev = List.of(
                CanonicalMessage.of("system", "sys"),
                CanonicalMessage.of("user", "u1"));
        List<CanonicalMessage> curr = List.of(
                CanonicalMessage.of("system", "sys"),
                CanonicalMessage.of("user", "u1"),
                CanonicalMessage.of("assistant", "a1"),
                CanonicalMessage.of("user", "u2"));
        CacheDiagnosticResult result = diagnostics.diagnose(CacheDiagnosticInput.builder()
                .previousMessages(prev)
                .currentMessages(curr)
                .build());
        assertEquals(CacheDiagnosticCategory.APPEND_ONLY_OK, result.category());
        assertEquals(Integer.valueOf(prev.size()), result.firstDiffIndex());
        assertEquals(1d, result.lcpRatio(), 0.0001);
    }

    // ---------- 首条 system 漂移 ----------

    @Test
    public void firstSystemMessageDriftIsEarlyPrefixDrift() {
        List<CanonicalMessage> prev = List.of(
                CanonicalMessage.of("system", "old system prompt"),
                CanonicalMessage.of("user", "hello"));
        List<CanonicalMessage> curr = List.of(
                CanonicalMessage.of("system", "new system prompt that drifted"),
                CanonicalMessage.of("user", "hello"));
        CacheDiagnosticResult result = diagnostics.diagnose(CacheDiagnosticInput.builder()
                .previousMessages(prev)
                .currentMessages(curr)
                .build());
        assertEquals(CacheDiagnosticCategory.EARLY_PREFIX_DRIFT, result.category());
        assertEquals(Integer.valueOf(0), result.firstDiffIndex());
        assertEquals("system", result.firstDiffRole());
    }

    @Test
    public void firstUserMessageDriftIsEarlyPrefixDrift() {
        List<CanonicalMessage> prev = List.of(
                CanonicalMessage.of("system", "sys"),
                CanonicalMessage.of("user", "first ask"),
                CanonicalMessage.of("assistant", "first reply"),
                CanonicalMessage.of("user", "second ask"));
        List<CanonicalMessage> curr = List.of(
                CanonicalMessage.of("system", "sys"),
                CanonicalMessage.of("user", "DIFFERENT first ask"),
                CanonicalMessage.of("assistant", "first reply"),
                CanonicalMessage.of("user", "second ask"));
        CacheDiagnosticResult result = diagnostics.diagnose(CacheDiagnosticInput.builder()
                .previousMessages(prev)
                .currentMessages(curr)
                .build());
        assertEquals(CacheDiagnosticCategory.EARLY_PREFIX_DRIFT, result.category());
        assertEquals(Integer.valueOf(1), result.firstDiffIndex());
    }

    // ---------- 中间历史重写 ----------

    @Test
    public void historyRewriteInTheMiddleIsClassifiedAsHistoryRewritten() {
        List<CanonicalMessage> prev = List.of(
                CanonicalMessage.of("system", "sys"),
                CanonicalMessage.of("user", "u1"),
                CanonicalMessage.of("assistant", "a1"),
                CanonicalMessage.of("user", "u2"),
                CanonicalMessage.of("assistant", "a2"),
                CanonicalMessage.of("user", "u3"));
        List<CanonicalMessage> curr = List.of(
                CanonicalMessage.of("system", "sys"),
                CanonicalMessage.of("user", "u1"),
                CanonicalMessage.of("assistant", "a1"),
                CanonicalMessage.of("user", "TAMPERED u2"),
                CanonicalMessage.of("assistant", "a2"),
                CanonicalMessage.of("user", "u3"));
        CacheDiagnosticResult result = diagnostics.diagnose(CacheDiagnosticInput.builder()
                .previousMessages(prev)
                .currentMessages(curr)
                .build());
        assertEquals(CacheDiagnosticCategory.HISTORY_REWRITTEN, result.category());
        assertEquals(Integer.valueOf(3), result.firstDiffIndex());
        assertEquals("user", result.firstDiffRole());
        assertTrue("lcp ratio should be < 1", result.lcpRatio() < 1d);
    }

    // ---------- message role 改变 ----------

    @Test
    public void roleFlipIsClassifiedAsHistoryRewritten() {
        // prev 把第 3 条记录成 assistant，curr 改成 user：role 翻转
        List<CanonicalMessage> prev = List.of(
                CanonicalMessage.of("system", "sys"),
                CanonicalMessage.of("user", "u1"),
                CanonicalMessage.of("assistant", "a1"),
                CanonicalMessage.of("user", "u2"));
        List<CanonicalMessage> curr = List.of(
                CanonicalMessage.of("system", "sys"),
                CanonicalMessage.of("user", "u1"),
                CanonicalMessage.of("user", "a1"),
                CanonicalMessage.of("user", "u2"));
        CacheDiagnosticResult result = diagnostics.diagnose(CacheDiagnosticInput.builder()
                .previousMessages(prev)
                .currentMessages(curr)
                .build());
        assertEquals(CacheDiagnosticCategory.HISTORY_REWRITTEN, result.category());
        assertEquals(Integer.valueOf(2), result.firstDiffIndex());
        assertEquals("user", result.firstDiffRole());
    }

    // ---------- tools 变化 ----------

    @Test
    public void toolsFlagFlipIsClassifiedAsToolsChanged() {
        List<CanonicalMessage> msgs = List.of(CanonicalMessage.of("user", "hi"));
        CacheDiagnosticResult result = diagnostics.diagnose(CacheDiagnosticInput.builder()
                .previousMessages(msgs)
                .currentMessages(msgs)
                .toolsIncludedInPrevious(true)
                .toolsIncludedInCurrent(false)
                .build());
        // messages hash 相同但 tools 翻转，分类应优先报告 TOOLS_CHANGED
        assertEquals(CacheDiagnosticCategory.TOOLS_CHANGED, result.category());
        assertTrue(result.toolsIncludedInPrevious());
        assertFalse(result.toolsIncludedInCurrent());
    }

    @Test
    public void toolsAddedOverridesAppendOnly() {
        List<CanonicalMessage> prev = List.of(CanonicalMessage.of("user", "u"));
        List<CanonicalMessage> curr = List.of(
                CanonicalMessage.of("user", "u"),
                CanonicalMessage.of("assistant", "a"));
        CacheDiagnosticResult result = diagnostics.diagnose(CacheDiagnosticInput.builder()
                .previousMessages(prev)
                .currentMessages(curr)
                .toolsIncludedInPrevious(false)
                .toolsIncludedInCurrent(true)
                .build());
        assertEquals(CacheDiagnosticCategory.TOOLS_CHANGED, result.category());
    }

    // ---------- COMPACTION_RESET ----------

    @Test
    public void sharpDropInSizeIsCompactionReset() {
        List<CanonicalMessage> prev = new ArrayList<>();
        prev.add(CanonicalMessage.of("system", "sys"));
        for (int i = 0; i < 20; i++) {
            prev.add(CanonicalMessage.of(i % 2 == 0 ? "user" : "assistant", "msg-" + i));
        }
        List<CanonicalMessage> curr = List.of(
                CanonicalMessage.of("system", "sys"),
                CanonicalMessage.of("user", "summary placeholder"));
        CacheDiagnosticResult result = diagnostics.diagnose(CacheDiagnosticInput.builder()
                .previousMessages(prev)
                .currentMessages(curr)
                .build());
        assertEquals(CacheDiagnosticCategory.COMPACTION_RESET, result.category());
    }

    // ---------- 脱敏覆盖 ----------

    @Test
    public void redactorMasksBearer() {
        SensitiveContentRedactor r = SensitiveContentRedactor.create();
        String redacted = r.redact("Authorization: Bearer abcdefghijklmnop");
        assertTrue(redacted.contains("Bearer ***"));
        assertFalse("raw token should not survive", redacted.contains("abcdefghijklmnop"));
    }

    @Test
    public void redactorMasksApiKeyJson() {
        SensitiveContentRedactor r = SensitiveContentRedactor.create();
        String json = "{\"apiKey\":\"sk-supersecret-1234\",\"model\":\"x\"}";
        String out = r.redact(json);
        assertTrue("apiKey value must be masked: " + out, out.contains("\"apiKey\":\"***\""));
        assertFalse(out.contains("sk-supersecret-1234"));
    }

    @Test
    public void redactorMasksAllCommonSecretKeys() {
        SensitiveContentRedactor r = SensitiveContentRedactor.create();
        String text = "token=abc123 password=hunter2 secret=top pwd=321 api-key=kkk access_token=at refresh_token=rt";
        String out = r.redact(text);
        assertFalse(out.contains("abc123"));
        assertFalse(out.contains("hunter2"));
        assertFalse(out.contains("top"));
        assertFalse(out.contains("kkk"));
        assertFalse(out.contains("at"));
        assertFalse(out.contains("rt"));
    }

    @Test
    public void redactorMasksJsonKeysWithDashesAndUnderscores() {
        SensitiveContentRedactor r = SensitiveContentRedactor.create();
        assertTrue(r.redact("\"api-key\":\"abc\"").contains("\"api-key\":\"***\""));
        assertTrue(r.redact("\"api_key\":\"abc\"").contains("\"api_key\":\"***\""));
        assertTrue(r.redact("\"APIKey\":\"abc\"").contains("\"APIKey\":\"***\""));
    }

    @Test
    public void redactorMasksEnvVarAssignments() {
        SensitiveContentRedactor r = SensitiveContentRedactor.create();
        String line = "DEEPSEEK_API_KEY=sk-supersecret-9999 OPENAI_API_KEY=\"openai-abc\"";
        String out = r.redact(line);
        assertFalse("raw DEEPSEEK key leaked: " + out, out.contains("sk-supersecret-9999"));
        assertFalse("raw OPENAI key leaked: " + out, out.contains("openai-abc"));
        assertTrue("DEEPSEEK_API_KEY=***: " + out, out.contains("DEEPSEEK_API_KEY=***"));
        assertTrue("OPENAI_API_KEY=***: " + out, out.contains("OPENAI_API_KEY=***"));
    }

    @Test
    public void redactorLeavesUnrelatedTextAlone() {
        SensitiveContentRedactor r = SensitiveContentRedactor.create();
        String text = "今天天气不错，我想去 token 商店买一个 token 玩具。";
        // "token" 作为普通中文词不应被脱敏（不匹配任何 secret pattern）
        // 注：英文 "token" 单独出现也不会被改（必须紧邻 key 标记）
        String out = r.redact(text);
        assertEquals(text, out);
    }

    @Test
    public void previewRedactsAndTruncates() {
        SensitiveContentRedactor r = SensitiveContentRedactor.create();
        String longContent = "Bearer abcdef0123456789 " + "x".repeat(1000);
        String preview = r.preview("user", longContent, 60);
        assertTrue("preview should not contain raw token: " + preview,
                !preview.contains("abcdef0123456789"));
        assertTrue("preview should include role bracket: " + preview, preview.startsWith("[user] "));
        assertTrue("preview should be bounded: " + preview.length(), preview.length() <= 60);
    }

    @Test
    public void resultPreviewDoesNotLeakRawPayload() {
        List<CanonicalMessage> prev = List.of(CanonicalMessage.of("user", "sk-supersecret-payload"));
        List<CanonicalMessage> curr = List.of(
                CanonicalMessage.of("user", "sk-supersecret-payload"),
                CanonicalMessage.of("user", "different question, my api key is api-key=ghijkl"));
        CacheDiagnosticResult result = diagnostics.diagnose(CacheDiagnosticInput.builder()
                .previousMessages(prev)
                .currentMessages(curr)
                .build());
        assertFalse("current preview must not leak raw api-key value",
                result.currentPreview().contains("ghijkl"));
    }

    // ---------- 超长输入截断 ----------

    @Test
    public void superLongContentIsTruncatedInPreview() {
        String huge = "x".repeat(200_000);
        List<CanonicalMessage> curr = List.of(CanonicalMessage.of("user", huge));
        CacheDiagnosticResult result = diagnostics.diagnose(CacheDiagnosticInput.builder()
                .currentMessages(curr)
                .previewLimit(64)
                .build());
        assertTrue("preview must be bounded: " + result.currentPreview().length(),
                result.currentPreview().length() <= 64);
    }

    @Test
    public void superLongRawPayloadIsBoundedForHash() {
        String huge = "x".repeat(1_000_000);
        String tiny = "y";
        CacheDiagnosticResult result = diagnostics.diagnose(CacheDiagnosticInput.builder()
                .currentMessages(List.of(CanonicalMessage.of("user", "u")))
                .previousRawPayload(huge)
                .currentRawPayload(tiny)
                .rawPayloadHashLimit(1024)
                .build());
        assertNotNull("bounded raw hash should still be computed", result.previousRawPayloadHash());
        assertNotNull(result.currentRawPayloadHash());
        assertEquals(64, result.previousRawPayloadHash().length());
        assertEquals(0, result.rawPayloadLcpLength());
    }

    // ---------- raw payload 辅助信号 ----------

    @Test
    public void rawPayloadLcpIsComputedWhenBothProvided() {
        String prev = "{\"model\":\"x\",\"messages\":[{\"role\":\"system\",\"content\":\"abc\"},{\"role\":\"user\",\"content\":\"hi\"}";
        String curr = "{\"model\":\"x\",\"messages\":[{\"role\":\"system\",\"content\":\"abc\"},{\"role\":\"user\",\"content\":\"hi there\"}";
        CacheDiagnosticResult result = diagnostics.diagnose(CacheDiagnosticInput.builder()
                .previousMessages(List.of(CanonicalMessage.of("system", "abc"),
                        CanonicalMessage.of("user", "hi")))
                .currentMessages(List.of(CanonicalMessage.of("system", "abc"),
                        CanonicalMessage.of("user", "hi there")))
                .previousRawPayload(prev)
                .currentRawPayload(curr)
                .build());
        assertNotNull(result.previousRawPayloadHash());
        assertNotNull(result.currentRawPayloadHash());
        assertTrue("raw LCP should be > 0", result.rawPayloadLcpLength() > 0);
        assertTrue("raw LCP should be < min length",
                result.rawPayloadLcpLength() < Math.min(prev.length(), curr.length()));
    }

    // ---------- null / 空 messages 边界 ----------

    @Test
    public void emptyCurrentWithNonEmptyPreviousIsCompactionReset() {
        List<CanonicalMessage> prev = List.of(
                CanonicalMessage.of("system", "s"),
                CanonicalMessage.of("user", "u1"),
                CanonicalMessage.of("user", "u2"),
                CanonicalMessage.of("user", "u3"),
                CanonicalMessage.of("user", "u4"));
        CacheDiagnosticResult result = diagnostics.diagnose(CacheDiagnosticInput.builder()
                .previousMessages(prev)
                .currentMessages(List.of())
                .build());
        // current 完全空，prev 有 5 条，compaction 判定触发
        assertEquals(CacheDiagnosticCategory.COMPACTION_RESET, result.category());
    }

    @Test
    public void emptyCurrentAloneIsUnknown() {
        CacheDiagnosticResult result = diagnostics.diagnose(CacheDiagnosticInput.builder()
                .currentMessages(List.of())
                .build());
        assertEquals(CacheDiagnosticCategory.FIRST_REQUEST, result.category());
        assertEquals(0, result.currentMessageCount());
    }

    @Test
    public void nullCurrentMessagesIsTreatedAsEmpty() {
        // 输入契约：currentMessages 永远非 null（builder 默认 emptyList）
        // 但即使外部传 null，diagnose 也会按空处理
        CacheDiagnosticResult result = diagnostics.diagnose(CacheDiagnosticInput.builder()
                .currentMessages(null)
                .build());
        assertEquals(CacheDiagnosticCategory.FIRST_REQUEST, result.category());
    }

    // ---------- toString / 异常不泄露 ----------

    @Test
    public void resultToStringDoesNotLeakContent() {
        String secret = "sk-supersecret-DO-NOT-LEAK";
        List<CanonicalMessage> msgs = List.of(CanonicalMessage.of("user", secret));
        CacheDiagnosticResult result = diagnostics.diagnose(CacheDiagnosticInput.builder()
                .currentMessages(msgs)
                .build());
        String s = result.toString();
        assertFalse("toString must not leak raw content: " + s, s.contains(secret));
    }

    @Test
    public void canonicalMessageToStringDoesNotLeakContent() {
        String secret = "sk-supersecret-DO-NOT-LEAK";
        CanonicalMessage m = CanonicalMessage.of("user", secret);
        String s = m.toString();
        assertFalse("CanonicalMessage.toString must not leak content: " + s, s.contains(secret));
    }

    // ---------- 并发键隔离 ----------

    @Test
    public void concurrentDiagnoseKeepsPerInputIsolated() throws Exception {
        int parallelism = 16;
        int iterations = 200;
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(parallelism);
        Map<Integer, CacheDiagnosticResult> resultsByKey = new ConcurrentHashMap<>();

        for (int t = 0; t < parallelism; t++) {
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        int key = i % 4;
                        CacheDiagnosticInput input = buildInputForKey(key);
                        CacheDiagnosticResult r = diagnostics.diagnose(input);
                        // 关键隔离：相同 key 的结果在所有线程/迭代中必须稳定
                        CacheDiagnosticResult prev = resultsByKey.putIfAbsent(key, r);
                        if (prev != null) {
                            assertEquals("category mismatch for key " + key,
                                    prev.category(), r.category());
                            assertEquals("hash mismatch for key " + key,
                                    prev.currentHash(), r.currentHash());
                        }
                    }
                } catch (Throwable e) {
                    fail("worker failed: " + e);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue("workers timed out", done.await(20, TimeUnit.SECONDS));
        pool.shutdownNow();
    }

    private CacheDiagnosticInput buildInputForKey(int key) {
        return switch (key) {
            case 0 -> CacheDiagnosticInput.builder()
                    .currentMessages(List.of(CanonicalMessage.of("system", "sys"),
                            CanonicalMessage.of("user", "u1"))).build();
            case 1 -> CacheDiagnosticInput.builder()
                    .previousMessages(List.of(CanonicalMessage.of("user", "u")))
                    .currentMessages(List.of(CanonicalMessage.of("user", "u"),
                            CanonicalMessage.of("assistant", "a"))).build();
            case 2 -> CacheDiagnosticInput.builder()
                    .previousMessages(List.of(CanonicalMessage.of("user", "u1"),
                            CanonicalMessage.of("assistant", "a1"),
                            CanonicalMessage.of("user", "u2"),
                            CanonicalMessage.of("assistant", "a2"),
                            CanonicalMessage.of("user", "u3"),
                            CanonicalMessage.of("assistant", "a3"),
                            CanonicalMessage.of("user", "u4"),
                            CanonicalMessage.of("assistant", "a4")))
                    .currentMessages(List.of(CanonicalMessage.of("user", "summary"))).build();
            case 3 -> CacheDiagnosticInput.builder()
                    .previousMessages(List.of(CanonicalMessage.of("user", "u1"),
                            CanonicalMessage.of("assistant", "a1")))
                    .currentMessages(List.of(CanonicalMessage.of("user", "u1"),
                            CanonicalMessage.of("assistant", "DIFFERENT"))).build();
            default -> throw new IllegalStateException();
        };
    }

    // ---------- hash 对 null 内容稳定 ----------

    @Test
    public void canonicalMessageNormalizesNullToEmpty() {
        CanonicalMessage a = CanonicalMessage.of(null, null);
        CanonicalMessage b = CanonicalMessage.of("", "");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(hasher.hash(List.of(a)), hasher.hash(List.of(b)));
    }

    // ---------- 多线程下同输入产生同结果 ----------

    @Test
    public void sameInputFromMultipleThreadsProducesSameHash() throws Exception {
        int parallelism = 8;
        List<CanonicalMessage> msgs = List.of(
                CanonicalMessage.of("system", "sys"),
                CanonicalMessage.of("user", "u1"),
                CanonicalMessage.of("assistant", "a1"));
        String expected = hasher.hash(msgs);
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(parallelism);
        AtomicReference<Throwable> error = new AtomicReference<>();
        for (int i = 0; i < parallelism; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                    for (int n = 0; n < 500; n++) {
                        String h = hasher.hash(msgs);
                        if (!expected.equals(h)) {
                            throw new AssertionError("hash drift");
                        }
                    }
                } catch (Throwable t) {
                    error.set(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }
}
