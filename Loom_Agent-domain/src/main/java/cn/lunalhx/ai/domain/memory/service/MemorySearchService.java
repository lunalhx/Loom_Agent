package cn.lunalhx.ai.domain.memory.service;

import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryVectorIndex;
import cn.lunalhx.ai.domain.memory.adapter.port.MemoryEmbeddingGateway;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemory;
import cn.lunalhx.ai.domain.memory.model.valobj.EmbeddingVector;
import cn.lunalhx.ai.domain.memory.model.valobj.MemorySearchHit;
import cn.lunalhx.ai.domain.memory.model.valobj.ScoredMemoryId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class MemorySearchService {

    private static final Logger log = LoggerFactory.getLogger(MemorySearchService.class);

    private static final double W_DISTANCE = 0.4;
    private static final double W_IMPORTANCE = 0.2;
    private static final double W_RECENCY = 0.2;
    private static final double W_KEYWORD = 0.2;

    private final AgentMemoryRepository memoryRepository;
    private final AgentMemoryVectorIndex vectorIndex;
    private final MemoryEmbeddingGateway embeddingGateway;
    private final int searchK;

    public MemorySearchService(AgentMemoryRepository memoryRepository,
                                AgentMemoryVectorIndex vectorIndex,
                                MemoryEmbeddingGateway embeddingGateway,
                                int searchK) {
        this.memoryRepository = memoryRepository;
        this.vectorIndex = vectorIndex;
        this.embeddingGateway = embeddingGateway;
        this.searchK = searchK;
    }

    public List<MemorySearchHit> search(String workspaceKey, String query, int limit) {
        if (vectorIndex.available()) {
            try {
                return vectorSearch(workspaceKey, query, limit);
            } catch (Exception e) {
                log.warn("Vector search failed, falling back to keyword: {}", e.getMessage());
            }
        }
        return keywordSearch(workspaceKey, query, limit);
    }

    private List<MemorySearchHit> vectorSearch(String workspaceKey, String query, int limit) {
        EmbeddingVector queryEmbedding = embeddingGateway.embed(query);
        List<ScoredMemoryId> scored = vectorIndex.search(workspaceKey, queryEmbedding, searchK);
        log.info("vectorSearch workspace={}, query={}..., scored={} hits",
                workspaceKey.substring(0, Math.min(16, workspaceKey.length())),
                query.substring(0, Math.min(50, query.length())),
                scored.size());
        if (scored.isEmpty()) {
            log.debug("vectorSearch returned 0 results for workspace={}, falling back to keyword", 
                    workspaceKey.substring(0, Math.min(16, workspaceKey.length())));
            return keywordSearch(workspaceKey, query, limit);
        }

        List<String> keywords = extractKeywords(query);
        List<AgentMemory> candidates = new ArrayList<>();
        for (ScoredMemoryId sid : scored) {
            Optional<AgentMemory> mem = memoryRepository.findById(sid.memoryId());
            mem.ifPresent(candidates::add);
        }

        return rerank(candidates, scored, keywords, limit);
    }

    private List<MemorySearchHit> keywordSearch(String workspaceKey, String query, int limit) {
        List<String> keywords = extractKeywords(query);
        List<AgentMemory> memories;
        if (keywords.isEmpty()) {
            memories = memoryRepository.findActive(workspaceKey, limit);
        } else {
            memories = memoryRepository.searchByKeywords(workspaceKey, keywords, limit);
        }
        return memories.stream()
                .map(m -> new MemorySearchHit(m, keywordOverlap(m, keywords), MemorySearchHit.SOURCE_KEYWORD))
                .collect(Collectors.toList());
    }

    private List<MemorySearchHit> rerank(List<AgentMemory> candidates,
                                          List<ScoredMemoryId> scored,
                                          List<String> keywords,
                                          int limit) {
        Map<String, Double> distanceMap = scored.stream()
                .collect(Collectors.toMap(ScoredMemoryId::memoryId, ScoredMemoryId::distance));

        return candidates.stream()
                .map(m -> {
                    Double dist = distanceMap.get(m.getMemoryId());
                    double distanceScore = dist != null ? Math.max(0.0, 1.0 - dist / 2.0) : 0.0;
                    double importanceScore = m.getImportance() / 100.0;

                    double recencyScore = 0.0;
                    if (m.getLastUsedAt() != null) {
                        long ageHours = Duration.between(m.getLastUsedAt(), Instant.now()).toHours();
                        recencyScore = Math.max(0.0, 1.0 - ageHours / (24.0 * 30.0));
                    }

                    double keywordScore = keywordOverlap(m, keywords);

                    double comprehensiveScore = W_DISTANCE * distanceScore
                            + W_IMPORTANCE * importanceScore
                            + W_RECENCY * recencyScore
                            + W_KEYWORD * keywordScore;

                    return new MemorySearchHit(m, comprehensiveScore, MemorySearchHit.SOURCE_VECTOR);
                })
                .sorted(Comparator.comparingDouble(MemorySearchHit::comprehensiveScore).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    public double keywordOverlap(AgentMemory memory, List<String> keywords) {
        if (keywords.isEmpty()) {
            return 1.0;
        }
        String text = (memory.getTitle() + " " + memory.getSummary() + " " + memory.getBody()).toLowerCase();
        int hits = 0;
        for (String kw : keywords) {
            if (text.contains(kw)) {
                hits++;
            }
        }
        return (double) hits / keywords.size();
    }

    private List<String> extractKeywords(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return Arrays.stream(query.toLowerCase().split("[\\s，,。.!！？?]+"))
                .filter(w -> w.length() >= 2)
                .collect(Collectors.toList());
    }
}
