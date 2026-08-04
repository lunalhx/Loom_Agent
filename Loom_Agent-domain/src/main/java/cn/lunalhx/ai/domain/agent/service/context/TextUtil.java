package cn.lunalhx.ai.domain.agent.service.context;

import java.util.ArrayList;
import java.util.List;

/**
 * Unicode code-point-aware helpers used by the context renderers so that
 * trimming never splits a surrogate pair.
 */
final class TextUtil {

    private TextUtil() {
    }

    static int length(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    /** First {@code max} code points of {@code value}. */
    static String head(String value, int max) {
        if (value == null || value.isEmpty() || max <= 0) {
            return "";
        }
        if (length(value) <= max) {
            return value;
        }
        int end = value.offsetByCodePoints(0, max);
        return value.substring(0, end);
    }

    /** Last {@code max} code points of {@code value}. */
    static String tail(String value, int max) {
        if (value == null || value.isEmpty() || max <= 0) {
            return "";
        }
        if (length(value) <= max) {
            return value;
        }
        int start = value.offsetByCodePoints(0, length(value) - max);
        return value.substring(start);
    }

    /**
     * Head/tail clipping bounded to {@code budget} code points: keeps a head
     * and a tail so that continuity is preserved.
     */
    static String clipHeadTail(String value, int budget) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (length(value) <= budget) {
            return value;
        }
        int head = (int) (budget * 0.75);
        int tail = budget - head;
        String h = head(value, head);
        String t = tail(value, tail);
        return h + "\n[...] " + t;
    }

    static List<String> nonEmptyLines(String value, int maxLines) {
        List<String> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        for (String line : value.split("\n", -1)) {
            if (!line.isBlank()) {
                result.add(line);
                if (result.size() >= maxLines) {
                    break;
                }
            }
        }
        return result;
    }

    /** Single-line normalization: newlines collapsed and clipped. */
    static String singleLine(String value, int max) {
        if (value == null) {
            return "";
        }
        return head(value.replace('\n', ' ').strip(), max);
    }
}
