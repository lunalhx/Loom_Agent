package cn.lunalhx.ai.domain.memory.service;

import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class MemorySelectionService {

    private final AgentMemoryRepository memoryRepository;
    private final MemorySearchService searchService;
    private final int maxSelected;
    private final int maxInjectedChars;

    public MemorySelectionService(AgentMemoryRepository memoryRepository,
                                   MemorySearchService searchService,
                                   int maxSelected,
                                   int maxInjectedChars) {
        this.memoryRepository = memoryRepository;
        this.searchService = searchService;
        this.maxSelected = maxSelected;
        this.maxInjectedChars = maxInjectedChars;
    }

    public SelectionResult select(String workspaceKey, String question) {
        List<AgentMemory> pinned = memoryRepository.findPinned(workspaceKey, 4);

        List<AgentMemory> candidates;
        if (searchService != null) {
            candidates = searchService.search(workspaceKey, question, maxSelected * 2);
        } else {
            List<AgentMemory> active = memoryRepository.findActive(workspaceKey, 200);
            candidates = active.stream()
                    .filter(m -> !pinned.contains(m))
                    .collect(Collectors.toList());
            candidates = keywordMatch(candidates, question);
        }

        List<AgentMemory> selected = new ArrayList<>(pinned);
        int charCount = totalChars(selected);
        for (AgentMemory m : candidates) {
            if (selected.size() >= maxSelected || charCount + m.getBody().length() > maxInjectedChars) {
                break;
            }
            if (!selected.contains(m)) {
                selected.add(m);
                charCount += m.getBody().length();
            }
        }

        return new SelectionResult(selected);
    }

    private List<AgentMemory> keywordMatch(List<AgentMemory> candidates, String question) {
        if (question == null || question.isBlank()) {
            return candidates;
        }
        String lower = question.toLowerCase();
        List<String> keywords = extractKeywords(lower);
        if (keywords.isEmpty()) {
            return candidates;
        }
        return candidates.stream()
                .sorted((a, b) -> Integer.compare(matchScore(b, keywords), matchScore(a, keywords)))
                .collect(Collectors.toList());
    }

    private List<String> extractKeywords(String question) {
        List<String> keywords = new ArrayList<>();
        for (String word : question.split("[\\s，,。.!！？?]+")) {
            if (word.length() >= 2) {
                keywords.add(word);
            }
        }
        return keywords;
    }

    private int matchScore(AgentMemory memory, List<String> keywords) {
        String text = (memory.getTitle() + " " + memory.getSummary() + " " + memory.getBody()).toLowerCase();
        int score = 0;
        for (String kw : keywords) {
            if (text.contains(kw)) {
                score++;
            }
        }
        return score;
    }

    private int totalChars(List<AgentMemory> memories) {
        return memories.stream().mapToInt(m -> m.getBody().length()).sum();
    }

    public record SelectionResult(List<AgentMemory> memories) {
        public static final SelectionResult EMPTY = new SelectionResult(Collections.emptyList());

        public boolean isEmpty() {
            return memories.isEmpty();
        }

        public Set<String> selectedIds() {
            return memories.stream().map(AgentMemory::getMemoryId).collect(Collectors.toSet());
        }

        public long aggregateVersion() {
            return memories.stream().mapToLong(AgentMemory::getVersion).sum();
        }
    }
}
