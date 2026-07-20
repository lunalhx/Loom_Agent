package cn.lunalhx.ai.domain.memory.service;

import cn.lunalhx.ai.domain.common.UntrustedContentSanitizer;
import cn.lunalhx.ai.domain.agent.model.valobj.MemoryRuntimeProperties;

import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemory;
import cn.lunalhx.ai.domain.memory.model.valobj.MemorySearchHit;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import cn.lunalhx.ai.domain.memory.model.valobj.MemoryType;

public class MemorySelectionService {

    private static final Logger log = LoggerFactory.getLogger(MemorySelectionService.class);

    private static final int WRAPPER_OVERHEAD = 100;

    private final AgentMemoryRepository memoryRepository;
    private final MemorySearchService searchService;
    private final int maxSelected;
    private final int maxInjectedChars;
    private final double minRelevanceScore;
    private final int pinnedLimit;

    public MemorySelectionService(AgentMemoryRepository memoryRepository,
                                   MemorySearchService searchService,
                                   int maxSelected,
                                   int maxInjectedChars,
                                   double minRelevanceScore,
                                   int pinnedLimit) {
        this.memoryRepository = memoryRepository;
        this.searchService = searchService;
        this.maxSelected = maxSelected;
        this.maxInjectedChars = maxInjectedChars;
        this.minRelevanceScore = minRelevanceScore;
        this.pinnedLimit = pinnedLimit;
    }

    public SelectionResult select(String workspaceKey, String question) {
        return select(workspaceKey, question, null);
    }

    public SelectionResult select(String workspaceKey, String question,
                                  MemoryRuntimeProperties runtimeProperties) {
        try {
            int selectedLimit = runtimeProperties == null ? maxSelected : runtimeProperties.getMaxSelected();
            int injectedCharLimit = runtimeProperties == null
                    ? maxInjectedChars : runtimeProperties.getMaxInjectedChars();
            double relevanceThreshold = runtimeProperties == null
                    ? minRelevanceScore : runtimeProperties.getMinRelevanceScore();
            int configuredPinnedLimit = runtimeProperties == null
                    ? pinnedLimit : runtimeProperties.getPinnedLimit();
            List<MemorySearchHit> pinnedHits = getPinnedHits(workspaceKey, configuredPinnedLimit);

            if (question == null || question.isBlank()) {
                return buildResult(pinnedHits, Collections.emptyList(), selectedLimit, injectedCharLimit);
            }

            List<MemorySearchHit> candidateHits;
            Set<String> pinnedIds = pinnedHits.stream()
                    .map(h -> h.memory().getMemoryId())
                    .collect(Collectors.toSet());

            if (searchService != null) {
                List<MemorySearchHit> searchHits = searchService.search(workspaceKey, question, selectedLimit * 2);
                candidateHits = searchHits.stream()
                        .filter(h -> h.memory().getStatus() == MemoryStatus.ACTIVE)
                        .filter(h -> !pinnedIds.contains(h.memory().getMemoryId()))
                        .collect(Collectors.toList());
            } else {
                List<AgentMemory> active = memoryRepository.findActive(workspaceKey, 200);
                List<String> keywords = extractKeywords(question);
                candidateHits = active.stream()
                        .filter(m -> !pinnedIds.contains(m.getMemoryId()))
                        .map(m -> {
                            double score = keywords.isEmpty() ? 1.0
                                    : keywordMatchScore(m, keywords);
                            return new MemorySearchHit(m, score, MemorySearchHit.SOURCE_KEYWORD);
                        })
                        .sorted(Comparator.comparingDouble(MemorySearchHit::comprehensiveScore).reversed())
                        .collect(Collectors.toList());
            }

            candidateHits = candidateHits.stream()
                    .filter(h -> h.comprehensiveScore() >= relevanceThreshold)
                    .sorted(Comparator.comparingDouble(MemorySearchHit::comprehensiveScore).reversed())
                    .collect(Collectors.toList());

            candidateHits = deduplicateByTitleKeyword(candidateHits);

            return buildResult(pinnedHits, candidateHits, selectedLimit, injectedCharLimit);
        } catch (Exception e) {
            log.warn("Memory selection failed, returning empty result. workspace={}, question={}: {}",
                    workspaceKey, question != null ? question.substring(0, Math.min(50, question.length())) : null,
                    e.getMessage());
            return SelectionResult.EMPTY;
        }
    }

    private List<MemorySearchHit> getPinnedHits(String workspaceKey, int configuredPinnedLimit) {
        if (memoryRepository == null) {
            return Collections.emptyList();
        }
        List<AgentMemory> pinned = memoryRepository.findPinned(workspaceKey, configuredPinnedLimit);
        return pinned.stream()
                .map(m -> new MemorySearchHit(m, 1.0, MemorySearchHit.SOURCE_PINNED))
                .collect(Collectors.toList());
    }

    private SelectionResult buildResult(List<MemorySearchHit> pinnedHits,
                                         List<MemorySearchHit> candidateHits,
                                         int selectedLimit,
                                         int injectedCharLimit) {
        List<MemorySearchHit> selectedHits = new ArrayList<>(pinnedHits);
        int charCount = totalChars(pinnedHits);

        for (MemorySearchHit hit : candidateHits) {
            if (selectedHits.size() >= selectedLimit) {
                break;
            }
            int hitChars = charCount(hit);
            int remaining = injectedCharLimit - WRAPPER_OVERHEAD - charCount;
            if (remaining <= 0) {
                break;
            }
            if (hitChars <= remaining) {
                selectedHits.add(hit);
                charCount += hitChars;
            } else if (selectedHits.size() == pinnedHits.size()) {
                AgentMemory m = hit.memory();
                int safetyMargin = 10;
                int maxBodyLen = remaining - (m.getTitle() != null ? m.getTitle().length() : 0)
                        - (m.getSummary() != null ? m.getSummary().length() : 0)
                        - (m.getType() != null ? m.getType().name().length() : 0)
                        - safetyMargin;
                if (maxBodyLen > 0) {
                    String body = m.getBody();
                    if (body != null && body.length() > maxBodyLen) {
                        body = body.substring(0, maxBodyLen) + "[...]";
                    }
                    AgentMemory truncated = AgentMemory.builder()
                            .memoryId(m.getMemoryId())
                            .workspaceKey(m.getWorkspaceKey())
                            .workspacePath(m.getWorkspacePath())
                            .type(m.getType())
                            .title(m.getTitle())
                            .summary(m.getSummary())
                            .body(body)
                            .status(m.getStatus())
                            .pinned(m.isPinned())
                            .importance(m.getImportance())
                            .sourceType(m.getSourceType())
                            .sourceRunId(m.getSourceRunId())
                            .contentHash(m.getContentHash())
                            .version(m.getVersion())
                            .usageCount(m.getUsageCount())
                            .lastUsedAt(m.getLastUsedAt())
                            .createdAt(m.getCreatedAt())
                            .updatedAt(m.getUpdatedAt())
                            .build();
                    MemorySearchHit truncatedHit = new MemorySearchHit(truncated, hit.comprehensiveScore(), hit.recallSource());
                    selectedHits.add(truncatedHit);
                }
                break;
            }
        }

        List<AgentMemory> selectedMemories = selectedHits.stream()
                .map(MemorySearchHit::memory)
                .collect(Collectors.toList());

        return new SelectionResult(selectedMemories, selectedHits, totalChars(selectedHits));
    }

    private int totalChars(List<MemorySearchHit> hits) {
        return hits.stream().mapToInt(this::charCount).sum();
    }

    private int charCount(MemorySearchHit hit) {
        AgentMemory m = hit.memory();
        int count = 0;
        if (m.getTitle() != null) count += m.getTitle().length();
        if (m.getSummary() != null) count += m.getSummary().length();
        if (m.getBody() != null) count += m.getBody().length();
        if (m.getType() != null) count += m.getType().name().length();
        return count;
    }

    private double keywordMatchScore(AgentMemory memory, List<String> keywords) {
        String text = (memory.getTitle() + " " + memory.getSummary() + " " + memory.getBody()).toLowerCase();
        int hits = 0;
        for (String kw : keywords) {
            if (text.contains(kw)) {
                hits++;
            }
        }
        return (double) hits / keywords.size();
    }

    private List<String> extractKeywords(String question) {
        if (question == null || question.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(question.toLowerCase().split("[\\s，,。.!！？?]+"))
                .filter(w -> w.length() >= 2)
                .collect(Collectors.toList());
    }

    public String renderWrappedText(SelectionResult result) {
        if (result == null || result.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<long_term_memories>\n");
        sb.append("以下是本工作区已确认的项目规范和约定，请在本次任务中严格遵守。\n\n");

        for (AgentMemory m : result.memories()) {
            String typeLabel = m.getType() != null ? m.getType().name() : "UNKNOWN";
            String guidance = typeGuidance(m.getType());
            sb.append("[").append(typeLabel).append("] ");
            if (m.isPinned()) {
                sb.append("[PINNED] ");
            }
            sb.append(escapeMemoryField(m.getTitle())).append("\n");
            sb.append(escapeMemoryField(m.getSummary())).append("\n");
            if (m.getBody() != null && !m.getBody().isBlank()) {
                sb.append(escapeMemoryField(m.getBody())).append("\n");
            }
            if (guidance != null && !guidance.isBlank()) {
                sb.append(guidance).append("\n");
            }
            sb.append("\n");
        }

        String body = sb.toString();
        body = body.replace("</long_term_memories>", "&lt;/long_term_memories>");
        return body + "</long_term_memories>";
    }

    private static String escapeMemoryField(String value) {
        if (value == null) {
            return "";
        }
        return UntrustedContentSanitizer.escapeXml(value);
    }

    private String typeGuidance(MemoryType type) {
        if (type == null) return null;
        return switch (type) {
            case PITFALL -> "[WARNING: 此条为已知坑，必须避免重复犯同样的错误]";
            case PREFERENCE -> "[此条为项目偏好，如与通用最佳实践冲突请优先遵循项目偏好]";
            case WORKFLOW -> "[此条为项目工作流规范，请严格遵循]";
            case PROJECT -> "[此条为项目事实，请作为已知条件使用]";
            case REFERENCE -> "[此条为参考资料，可按需查阅]";
        };
    }

    private List<MemorySearchHit> deduplicateByTitleKeyword(List<MemorySearchHit> hits) {
        List<MemorySearchHit> result = new ArrayList<>();
        Set<String> seenKeywords = new HashSet<>();

        for (MemorySearchHit hit : hits) {
            String title = hit.memory().getTitle();
            if (title == null || title.isBlank()) {
                result.add(hit);
                continue;
            }
            Set<String> titleWords = Arrays.stream(title.toLowerCase()
                    .split("[\\s,，。.!！？?;；:：()（）\\[\\]\"']+"))
                    .filter(w -> w.length() >= 2)
                    .collect(Collectors.toSet());

            boolean isDuplicate = false;
            for (String seen : seenKeywords) {
                Set<String> seenWords = Arrays.stream(seen.toLowerCase()
                        .split("[\\s,，。.!！？?;；:：()（）\\[\\]\"']+"))
                        .filter(w -> w.length() >= 2)
                        .collect(Collectors.toSet());
                double jaccard = jaccardSimilarity(titleWords, seenWords);
                if (jaccard > 0.6) {
                    isDuplicate = true;
                    break;
                }
            }

            if (!isDuplicate) {
                result.add(hit);
                seenKeywords.add(title);
            }
        }
        return result;
    }

    private double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    public record SelectionResult(List<AgentMemory> memories, List<MemorySearchHit> hits, int totalChars) {
        public static final SelectionResult EMPTY = new SelectionResult(Collections.emptyList(), Collections.emptyList(), 0);

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
