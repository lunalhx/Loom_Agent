package cn.lunalhx.ai.domain.memory.service;

import cn.lunalhx.ai.domain.memory.adapter.port.DurableMemoryRepository;
import cn.lunalhx.ai.domain.memory.model.MemoryEntry;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Promotes a finished run's final answer into workspace durable memory.
 *
 * <p>Promotion requires all of:
 * <ol>
 *   <li>An explicit user "remember/save" intent in the original question.</li>
 *   <li>A structured final conclusion in Chinese or English.</li>
 *   <li>None of the rejection shapes: secrets, logs/stdout/stderr/traceback,
 *       transient task state, or noisy/over-long text.</li>
 * </ol>
 *
 * <p>A new conclusion for the same topic+subject replaces the old entry.
 */
public final class MemoryPromotionService {

    public static final List<String> TOPICS = List.of(
            "project_conventions", "key_decisions", "dependency_facts", "user_preferences");

    private static final Pattern REMEMBER_INTENT_ZH = Pattern.compile("(记住|记得|保存|记录|牢记)");
    private static final Pattern REMEMBER_INTENT_EN = Pattern.compile("(?i)(remember|save|memorize|record|keep in mind)");

    private static final Pattern SECRET_SHAPE = Pattern.compile(
            "(?i)(api[_-]?key|secret|password|token|private[_-]?key|BEGIN [A-Z ]*PRIVATE KEY|sk-[A-Za-z0-9]{8,})");
    private static final Pattern LOG_SHAPE = Pattern.compile(
            "(?i)(^|\\n)(stdout|stderr|traceback|\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}|at \\S+\\.\\w+\\(\\S+\\.java:\\d+\\))");
    private static final Pattern TRANSIENT_STATE = Pattern.compile("(正在|进行中|待完成|步骤 \\d+|step \\d+|进行到)");

    private static final int MAX_CONTENT_CHARS = 500;
    private static final int MAX_SUBJECT_CHARS = 80;

    private final DurableMemoryRepository repository;

    public MemoryPromotionService(DurableMemoryRepository repository) {
        this.repository = repository;
    }

    /** Try to promote the final answer. Returns the saved entry or empty. */
    public Optional<MemoryEntry> promote(String userQuestion, String finalAnswer, String sourceRunId) {
        if (!hasRememberIntent(userQuestion)) {
            return Optional.empty();
        }
        String content = normalize(finalAnswer);
        if (!isStructuredConclusion(content)) {
            return Optional.empty();
        }
        if (isRejectedShape(content)) {
            return Optional.empty();
        }
        String topic = classifyTopic(userQuestion, content);
        String subject = extractSubject(userQuestion, content);
        if (subject == null || subject.isBlank()) {
            return Optional.empty();
        }
        MemoryEntry entry = MemoryEntry.builder()
                .schemaVersion(MemoryEntry.CURRENT_SCHEMA_VERSION)
                .topic(topic)
                .subject(subject)
                .content(content)
                .sourceRunId(sourceRunId)
                .createdAt(Instant.now())
                .build();
        return Optional.of(repository.upsert(entry));
    }

    public static boolean hasRememberIntent(String text) {
        if (text == null) {
            return false;
        }
        return REMEMBER_INTENT_ZH.matcher(text).find() || REMEMBER_INTENT_EN.matcher(text).find();
    }

    static boolean isStructuredConclusion(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        // needs at least one sentence-closing punctuation in zh/en
        return content.contains("。") || content.contains(".") || content.contains("！") || content.contains("！");
    }

    static boolean isRejectedShape(String content) {
        if (content == null || content.length() > MAX_CONTENT_CHARS) {
            return true;
        }
        if (SECRET_SHAPE.matcher(content).find()) {
            return true;
        }
        if (LOG_SHAPE.matcher(content).find()) {
            return true;
        }
        return TRANSIENT_STATE.matcher(content).find();
    }

    static String classifyTopic(String question, String content) {
        String combined = (question == null ? "" : question) + "\n" + content;
        String lower = combined.toLowerCase(Locale.ROOT);
        if (lower.contains("约定") || lower.contains("规范") || lower.contains("convention")
                || lower.contains("风格") || lower.contains("style")) {
            return "project_conventions";
        }
        if (lower.contains("决策") || lower.contains("决定") || lower.contains("decision")
                || lower.contains("选择") || lower.contains("选用")) {
            return "key_decisions";
        }
        if (lower.contains("依赖") || lower.contains("版本") || lower.contains("dependency")
                || lower.contains("library") || lower.contains("库 ") || lower.contains("maven") || lower.contains("pom")) {
            return "dependency_facts";
        }
        if (lower.contains("偏好") || lower.contains("喜欢") || lower.contains("prefer")
                || lower.contains("习惯")) {
            return "user_preferences";
        }
        return "project_conventions";
    }

    static String extractSubject(String question, String content) {
        // prefer a quoted name or "X 使用/采用 Y" style subject
        String subject = null;
        java.util.regex.Matcher zh = Pattern.compile(
                "([\\u4e00-\\u9fa5A-Za-z0-9._/-]{2,40})(?:使用|采用|选择|遵循|依赖|需要|必须|应当|改用|换用|升级|引入)")
                .matcher(content);
        if (zh.find()) {
            subject = zh.group(1);
        }
        if (subject == null) {
            subject = firstMeaningfulLine(question);
        }
        if (subject == null) {
            subject = firstMeaningfulLine(content);
        }
        if (subject == null || subject.isBlank()) {
            return null;
        }
        return subject.length() > MAX_SUBJECT_CHARS ? subject.substring(0, MAX_SUBJECT_CHARS) : subject;
    }

    private static String firstMeaningfulLine(String text) {
        if (text == null) {
            return null;
        }
        for (String line : text.split("\n")) {
            String s = line.strip().replaceAll("^(记住|记得|请记住|Please remember|Remember|记住：)", "").strip();
            if (!s.isBlank() && s.length() >= 2) {
                return s.length() > MAX_SUBJECT_CHARS ? s.substring(0, MAX_SUBJECT_CHARS) : s;
            }
        }
        return null;
    }

    private static String normalize(String answer) {
        if (answer == null) {
            return "";
        }
        return answer.strip().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip();
    }
}
