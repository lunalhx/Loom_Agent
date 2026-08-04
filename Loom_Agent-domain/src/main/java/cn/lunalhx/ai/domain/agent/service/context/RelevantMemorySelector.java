package cn.lunalhx.ai.domain.agent.service.context;

import cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Selects the most relevant working-memory notes for the current request.
 *
 * <p>Only in-session notes and file summaries are considered (no embedding, no
 * cross-session durable memory). Scoring is a deterministic blend of tag hits,
 * keyword overlap (transparent tokenization on both English identifiers and
 * Chinese text), recency, and sequence; the top {@code limit} notes win. The
 * per-note budget is then spread fairly across the selected notes.
 */
final class RelevantMemorySelector {

    private static final double TAG_WEIGHT = 2.0;
    private static final double KEYWORD_WEIGHT = 1.0;
    private static final double RECENCY_WEIGHT = 0.5;
    private static final double SEQUENCE_WEIGHT = 0.25;

    /** Select the top relevant notes, returning a copy ordered by recency (newest first). */
    List<ScoredNote> select(WorkingContextMemory wm, String requestText, int limit) {
        List<ScoredNote> scored = new ArrayList<>();
        if (wm == null) {
            return scored;
        }
        int effectiveLimit = limit <= 0 ? 3 : limit;

        Set<String> requestTokens = tokenize(requestText);

        List<WorkingContextMemory.MemoryNote> notes = wm.notes();
        long maxSequence = 0;
        for (WorkingContextMemory.MemoryNote n : notes) {
            maxSequence = Math.max(maxSequence, n.sequence());
        }

        for (WorkingContextMemory.MemoryNote n : notes) {
            double score = score(n, requestTokens, maxSequence);
            scored.add(new ScoredNote(n, score));
        }
        for (WorkingContextMemory.FileSummary fs : wm.fileSummaries().values()) {
            String body = fs.path() + " " + fs.summary();
            double score = keywordScore(body, requestTokens) * KEYWORD_WEIGHT;
            scored.add(new ScoredNote(
                    WorkingContextMemory.MemoryNote.builder()
                            .text(fs.path() + ": " + fs.summary())
                            .tags(List.of("file_summary", fs.path()))
                            .source(fs.path())
                            .createdAt(fs.createdAt())
                            .sequence(0L)
                            .kind("file_summary")
                            .build(),
                    score));
        }

        scored.sort(Comparator.comparingDouble((ScoredNote s) -> s.score).reversed());
        if (scored.size() > effectiveLimit) {
            scored = new ArrayList<>(scored.subList(0, effectiveLimit));
        }
        // Render newest-first.
        scored.sort(Comparator.comparing((ScoredNote s) -> s.note.createdAt()).reversed());
        return scored;
    }

    private double score(WorkingContextMemory.MemoryNote note, Set<String> requestTokens, long maxSequence) {
        double tagHits = 0;
        if (note.tags() != null) {
            for (String tag : note.tags()) {
                if (requestTokens.contains(tag.toLowerCase(Locale.ROOT))) {
                    tagHits++;
                }
            }
        }
        double tagScore = tagHits * TAG_WEIGHT;
        double keywordScore = keywordScore(note.text(), requestTokens) * KEYWORD_WEIGHT;
        long seq = note.sequence();
        double recencyScore = RECENCY_WEIGHT * (maxSequence == 0 ? 0.0 : (double) seq / maxSequence);
        return tagScore + keywordScore + recencyScore + SEQUENCE_WEIGHT;
    }

    private double keywordScore(String text, Set<String> requestTokens) {
        if (requestTokens.isEmpty()) {
            return 0;
        }
        Set<String> tokens = tokenize(text);
        int overlap = 0;
        for (String t : tokens) {
            if (requestTokens.contains(t)) {
                overlap++;
            }
        }
        return overlap;
    }

    /** Transparent tokenization for both ASCII identifiers and CJK text. */
    static Set<String> tokenize(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null) {
            return result;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (c >= 0x4E00 && c <= 0x9FFF || c >= 0x3400 && c <= 0x4DBF) {
                    // CJK: flush pending ascii token, then emit single char.
                    flush(result, ascii);
                    result.add(String.valueOf(c));
                } else {
                    ascii.append(c);
                }
            } else {
                flush(result, ascii);
            }
        }
        flush(result, ascii);
        return result;
    }

    private static void flush(Set<String> result, StringBuilder ascii) {
        if (ascii.length() > 0) {
            result.add(ascii.toString());
            ascii.setLength(0);
        }
    }

    record ScoredNote(WorkingContextMemory.MemoryNote note, double score) {
    }
}
