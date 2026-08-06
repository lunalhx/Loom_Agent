package cn.lunalhx.ai.domain.agent.service.context;

import cn.lunalhx.ai.domain.memory.model.MemoryEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Selects the most relevant workspace durable-memory entries for the current
 * request. Scoring is topic + subject + keyword overlap plus recency; the
 * section has its own char budget and never enters the stable prefix. No
 * vector database is involved.
 */
final class DurableMemorySelector {

    private final Supplier<List<MemoryEntry>> provider;

    DurableMemorySelector(Supplier<List<MemoryEntry>> provider) {
        this.provider = provider;
    }

    List<ScoredEntry> select(String requestText, int limit, int budgetChars) {
        List<ScoredEntry> result = new ArrayList<>();
        if (provider == null) {
            return result;
        }
        List<MemoryEntry> entries;
        try {
            entries = provider.get();
        } catch (Exception e) {
            return result;
        }
        if (entries == null || entries.isEmpty()) {
            return result;
        }
        int effectiveLimit = limit <= 0 ? 3 : limit;
        Set<String> requestTokens = RelevantMemorySelector.tokenize(requestText);
        List<ScoredEntry> scored = new ArrayList<>();
        for (MemoryEntry e : entries) {
            scored.add(new ScoredEntry(e, score(e, requestTokens)));
        }
        scored.sort(Comparator.comparingDouble((ScoredEntry s) -> s.score).reversed());
        if (scored.size() > effectiveLimit) {
            scored = new ArrayList<>(scored.subList(0, effectiveLimit));
        }
        // newest first
        scored.sort(Comparator.comparing((ScoredEntry s) -> s.entry.getUpdatedAt() != null
                        ? s.entry.getUpdatedAt() : s.entry.getCreatedAt() != null
                        ? s.entry.getCreatedAt() : Instant.now())
                .reversed());
        int used = 0;
        for (ScoredEntry s : scored) {
            int chars = s.entry.getContent().length() + s.entry.getTopic().length() + 8;
            if (used + chars > budgetChars) {
                break;
            }
            result.add(s);
            used += chars;
        }
        return result;
    }

    private double score(MemoryEntry entry, Set<String> requestTokens) {
        if (requestTokens.isEmpty()) {
            return 0;
        }
        double overlap = 0;
        for (String token : RelevantMemorySelector.tokenize(
                entry.getTopic() + " " + entry.getSubject() + " " + entry.getContent())) {
            if (requestTokens.contains(token)) {
                overlap++;
            }
        }
        return overlap;
    }

    record ScoredEntry(MemoryEntry entry, double score) {
    }
}
