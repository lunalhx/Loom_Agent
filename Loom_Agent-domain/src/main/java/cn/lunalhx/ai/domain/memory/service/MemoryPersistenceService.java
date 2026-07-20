package cn.lunalhx.ai.domain.memory.service;

import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemory;
import cn.lunalhx.ai.domain.memory.model.entity.MemoryExtractionPayload;
import cn.lunalhx.ai.domain.memory.model.valobj.MemorySourceType;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryStatus;
import cn.lunalhx.ai.domain.memory.service.MemoryExtractionService.ExtractedMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class MemoryPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(MemoryPersistenceService.class);

    private final AgentMemoryRepository memoryRepository;
    private final int maxActive;

    public MemoryPersistenceService(AgentMemoryRepository memoryRepository, int maxActive) {
        this.memoryRepository = memoryRepository;
        this.maxActive = maxActive;
    }

    public List<AgentMemory> persist(MemoryExtractionPayload payload,
                                      List<ExtractedMemory> extracted,
                                      String sourceRunId,
                                      String fallbackWorkspaceKey) {
        return persist(payload, extracted, sourceRunId, fallbackWorkspaceKey, maxActive);
    }

    public List<AgentMemory> persist(MemoryExtractionPayload payload,
                                     List<ExtractedMemory> extracted,
                                     String sourceRunId,
                                     String fallbackWorkspaceKey,
                                     int activeLimit) {
        String workspacePath = payload.getWorkspacePath();
        String workspaceKey;
        if ((workspacePath == null || workspacePath.isBlank())
                && fallbackWorkspaceKey != null && !fallbackWorkspaceKey.isBlank()) {
            workspaceKey = fallbackWorkspaceKey;
        } else {
            workspaceKey = WorkspaceKeyUtil.compute(workspacePath == null ? "" : workspacePath);
        }

        List<AgentMemory> existingForRun = memoryRepository.findBySourceRunId(sourceRunId);
        if (!existingForRun.isEmpty()) {
            return existingForRun;
        }

        List<AgentMemory> saved = new ArrayList<>();
        for (ExtractedMemory em : extracted) {
            List<AgentMemory> dupes = memoryRepository.findByContentHash(workspaceKey, em.contentHash());
            if (dupes != null && !dupes.isEmpty()) {
                continue;
            }

            AgentMemory conflict = findSimilarMemory(workspaceKey, em);
            if (conflict != null) {
                AgentMemory updated = AgentMemory.builder()
                        .memoryId(conflict.getMemoryId())
                        .workspaceKey(conflict.getWorkspaceKey())
                        .workspacePath(conflict.getWorkspacePath())
                        .type(em.type())
                        .title(em.title())
                        .summary(em.summary())
                        .body(em.body())
                        .status(conflict.getStatus())
                        .pinned(conflict.isPinned())
                        .importance(Math.max(conflict.getImportance(), em.importance()))
                        .sourceType(conflict.getSourceType())
                        .sourceRunId(conflict.getSourceRunId())
                        .contentHash(em.contentHash())
                        .version(conflict.getVersion())
                        .usageCount(conflict.getUsageCount())
                        .lastUsedAt(conflict.getLastUsedAt())
                        .createdAt(conflict.getCreatedAt())
                        .updatedAt(Instant.now())
                        .build();
                try {
                    memoryRepository.save(updated);
                    saved.add(updated);
                    log.info("Updated conflicting memory: oldTitle={}, newTitle={}",
                            conflict.getTitle(), em.title());
                } catch (Exception e) {
                    log.warn("Failed to update conflicting memory: title={}, error={}",
                            em.title(), e.getMessage());
                }
                continue;
            }

            int activeCount = memoryRepository.countActive(workspaceKey);
            if (activeCount >= activeLimit) {
                log.info("Memory capacity reached for workspace {}: {}/{}", workspaceKey, activeCount, activeLimit);
                break;
            }

            String memoryId = sha256(sourceRunId + "|" + em.contentHash());

            AgentMemory memory = AgentMemory.builder()
                    .memoryId(memoryId)
                    .workspaceKey(workspaceKey)
                    .workspacePath(workspacePath == null ? "" : workspacePath)
                    .type(em.type())
                    .title(em.title())
                    .summary(em.summary())
                    .body(em.body())
                    .status(MemoryStatus.ACTIVE)
                    .pinned(false)
                    .importance(em.importance())
                    .sourceType(MemorySourceType.AUTO_EXTRACTION)
                    .sourceRunId(sourceRunId)
                    .contentHash(em.contentHash())
                    .version(0)
                    .usageCount(0)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            try {
                memoryRepository.save(memory);
                saved.add(memory);
            } catch (Exception e) {
                log.warn("Failed to save extracted memory: title={}, error={}",
                        em.title(), e.getMessage());
            }
        }
        return saved;
    }

    private AgentMemory findSimilarMemory(String workspaceKey, ExtractedMemory em) {
        if (em.title() == null || em.title().isBlank()) {
            return null;
        }
        List<AgentMemory> existing = memoryRepository.findActive(workspaceKey, 200);
        Set<String> titleWords = tokenize(em.title());

        for (AgentMemory m : existing) {
            if (m.getType() != em.type()) {
                continue;
            }
            if (m.getTitle() == null || m.getTitle().isBlank()) {
                continue;
            }
            Set<String> existingWords = tokenize(m.getTitle());
            double jaccard = jaccard(titleWords, existingWords);
            if (jaccard > 0.6) {
                return m;
            }
        }
        return null;
    }

    private static Set<String> tokenize(String title) {
        return Arrays.stream(title.toLowerCase()
                .split("[\\s,，。.!！？?;；:：()（）\\[\\]\"']+"))
                .filter(w -> w.length() >= 2)
                .collect(Collectors.toSet());
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
