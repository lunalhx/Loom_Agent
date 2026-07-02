package cn.lunalhx.ai.infrastructure.gateway.diagnostics;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对单条消息 / 整段 payload 做脱敏。
 *
 * <p>覆盖：
 * <ul>
 *   <li>Bearer 头部：`Authorization: Bearer abc...` → `Bearer ***`；</li>
 *   <li>常见 JSON 键值：`apiKey/token/secret/password/...` → 脱敏为 `***`；</li>
 *   <li>URL 查询串中的 `api_key=...` 等；</li>
 *   <li>常见环境变量赋值：`DEEPSEEK_API_KEY=...` → `***`；</li>
 * </ul>
 *
 * <p>本类无状态，Pattern 都是 static final，线程安全。
 * <p>脱敏按「先匹配 key 名再替换 value」的方式，不会误伤普通词（例如「token」出现在句子里不会变形）。
 */
public final class SensitiveContentRedactor {

    private static final String REDACTED = "***";

    // Authorization: Bearer xxx
    private static final Pattern BEARER = Pattern.compile(
            "(?i)(\\bBearer\\s+)([A-Za-z0-9._\\-]+)");

    // "apiKey" / "api-key" / "api_key" / "apikey" / "token" / "secret" / "password" / "passwd" / "pwd"
    // "authorization" / "accessToken" / "refreshToken" 等
    private static final Pattern JSON_SECRET_KEY = Pattern.compile(
            "(?i)\"(api[_-]?key|apikey|token|secret|password|passwd|pwd" +
                    "|authorization|access[_-]?token|refresh[_-]?token|client[_-]?secret)\"\\s*:\\s*" +
                    "(\"(?:\\\\.|[^\"\\\\])*\"|[^,}\\]\\s]+)");

    // URL query: ?api_key=abc&token=...
    private static final Pattern URL_SECRET_PARAM = Pattern.compile(
            "(?i)([?&](?:api[_-]?key|apikey|token|secret|password|passwd|pwd" +
                    "|access[_-]?token|refresh[_-]?token|client[_-]?secret|sig|signature)" +
                    "=)([^&\\s\"]+)");

    // 通用 key=value（无 ?/& 前缀；常见于日志、stack trace、application.properties）
    // 不包含 authorization：Authorization 头由 BEARER 单独处理，避免覆盖 "Bearer xxx" 的语义
    private static final Pattern BARE_SECRET_ASSIGN = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_\\-])(api[_-]?key|apikey|token|secret|password|passwd|pwd" +
                    "|access[_-]?token|refresh[_-]?token|client[_-]?secret)" +
                    "\\s*[:=]\\s*" +
                    "(\"(?:\\\\.|[^\"\\\\])*\"|'(?:[^'\\\\]|\\\\.)*'|[^\\s,;}{\"']+)");

    // 环境变量赋值（shell / .env / 日志）
    private static final Pattern ENV_VAR_ASSIGN = Pattern.compile(
            "(?i)\\b(API[_-]?KEY|API[_-]?TOKEN|API[_-]?SECRET|ACCESS[_-]?TOKEN|REFRESH[_-]?TOKEN" +
                    "|SECRET[_-]?KEY|DB[_-]?PASSWORD|DEEPSEEK[_-]?API[_-]?KEY|OPENAI[_-]?API[_-]?KEY" +
                    "|ANTHROPIC[_-]?API[_-]?KEY|AWS[_-]?SECRET[_-]?ACCESS[_-]?KEY|GITHUB[_-]?TOKEN" +
                    "|SLACK[_-]?TOKEN|HUGGINGFACE[_-]?TOKEN|HF[_-]?TOKEN" +
                    "|LOOM[_-]?AGENT[_-]?API[_-]?KEY)\\s*[:=]\\s*" +
                    "(\"(?:\\\\.|[^\"\\\\])*\"|'(?:[^'\\\\]|\\\\.)*'|[^\\s,;}{\"']+)");

    private SensitiveContentRedactor() {
    }

    public static SensitiveContentRedactor create() {
        return new SensitiveContentRedactor();
    }

    /**
     * 对一段文本做脱敏。null 输入返回 null。
     */
    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        out = replaceAll(out, BEARER, m -> m.group(1) + REDACTED);
        out = replaceAll(out, JSON_SECRET_KEY, m -> "\"" + m.group(1) + "\":\"" + REDACTED + "\"");
        out = replaceAll(out, BARE_SECRET_ASSIGN, m -> m.group(1) + "=" + REDACTED);
        out = replaceAll(out, URL_SECRET_PARAM, m -> m.group(1) + REDACTED);
        out = replaceAll(out, ENV_VAR_ASSIGN, m -> m.group(1) + "=" + REDACTED);
        return out;
    }

    /**
     * 把 (role, content) 渲染为带长度上限的预览。content 会被脱敏并截断；role 不脱敏。
     */
    public String preview(String role, String content, int limit) {
        int cap = Math.max(8, limit);
        String safeRole = role == null ? "" : role;
        String redacted = redact(content);
        if (redacted == null) {
            redacted = "";
        }
        String body = "[" + safeRole + "] " + redacted;
        return truncate(body, cap);
    }

    static String truncate(String s, int limit) {
        if (s == null) {
            return "";
        }
        if (s.length() <= limit) {
            return s;
        }
        // 留 1 个字符给省略号
        if (limit <= 1) {
            return s.substring(0, limit);
        }
        return s.substring(0, limit - 1) + "…";
    }

    private static String replaceAll(String input, Pattern pattern, java.util.function.Function<Matcher, String> replacer) {
        Matcher m = pattern.matcher(input);
        StringBuilder sb = new StringBuilder(input.length());
        while (m.find()) {
            String replacement = replacer.apply(m);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
