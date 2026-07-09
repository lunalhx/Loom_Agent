package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemory;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryAgentMemoryRepository implements AgentMemoryRepository {

    private final Map<String, AgentMemory> store = new ConcurrentHashMap<>();
    private final int maxActive;

    public InMemoryAgentMemoryRepository() {
        this.maxActive = 0;
    }

    public InMemoryAgentMemoryRepository(int maxActive) {
        this.maxActive = maxActive;
    }

    @Override
    public AgentMemory save(AgentMemory memory) {
        String workspaceKey = memory.getWorkspaceKey();
        if (maxActive > 0) {
            long activeCount = store.values().stream()
                    .filter(m -> workspaceKey.equals(m.getWorkspaceKey()) && m.getStatus() == MemoryStatus.ACTIVE)
                    .count();
            if (activeCount >= maxActive) {
                findWeakestCandidate(workspaceKey).ifPresent(w -> updateStatus(w.getMemoryId(), MemoryStatus.ARCHIVED, w.getVersion()));
            }
        }
        String id = memory.getMemoryId() != null ? memory.getMemoryId() : UUID.randomUUID().toString();
        AgentMemory saved = AgentMemory.builder()
                .memoryId(id)
                .workspaceKey(memory.getWorkspaceKey())
                .workspacePath(memory.getWorkspacePath())
                .type(memory.getType())
                .title(memory.getTitle())
                .summary(memory.getSummary())
                .body(memory.getBody())
                .status(memory.getStatus())
                .pinned(memory.isPinned())
                .importance(memory.getImportance())
                .sourceType(memory.getSourceType())
                .sourceRunId(memory.getSourceRunId())
                .contentHash(memory.getContentHash())
                .version(memory.getVersion() + 1)
                .usageCount(memory.getUsageCount())
                .lastUsedAt(memory.getLastUsedAt())
                .createdAt(memory.getCreatedAt() != null ? memory.getCreatedAt() : Instant.now())
                .updatedAt(Instant.now())
                .build();
        store.put(id, saved);
        return saved;
    }

    @Override
    public Optional<AgentMemory> findById(String memoryId) {
        return Optional.ofNullable(store.get(memoryId));
    }

    @Override
    public List<AgentMemory> findActive(String workspaceKey, int limit) {
        return store.values().stream()
                .filter(m -> workspaceKey.equals(m.getWorkspaceKey()) && m.getStatus() == MemoryStatus.ACTIVE)
                .sorted(Comparator.comparing(AgentMemory::isPinned).reversed()
                        .thenComparing(m -> m.getLastUsedAt() != null ? m.getLastUsedAt() : Instant.EPOCH,
                                Comparator.reverseOrder()))
                .limit(limit)
                .toList();
    }

    @Override
    public List<AgentMemory> findPinned(String workspaceKey, int limit) {
        return store.values().stream()
                .filter(m -> workspaceKey.equals(m.getWorkspaceKey())
                        && m.getStatus() == MemoryStatus.ACTIVE
                        && m.isPinned())
                .limit(limit)
                .toList();
    }

    @Override
    public List<AgentMemory> searchByKeywords(String workspaceKey, List<String> keywords, int limit) {
        String joined = String.join(" ", keywords).toLowerCase();
        return store.values().stream()
                .filter(m -> workspaceKey.equals(m.getWorkspaceKey())
                        && m.getStatus() == MemoryStatus.ACTIVE
                        && (m.getTitle().toLowerCase().contains(joined)
                        || m.getSummary().toLowerCase().contains(joined)
                        || m.getBody().toLowerCase().contains(joined)))
                .limit(limit)
                .toList();
    }

    @Override
    public boolean updateUsage(String memoryId, long expectedVersion) {
        AgentMemory m = store.get(memoryId);
        if (m == null || m.getVersion() != expectedVersion) return false;
        store.put(memoryId, AgentMemory.builder()
                .memoryId(m.getMemoryId())
                .workspaceKey(m.getWorkspaceKey())
                .workspacePath(m.getWorkspacePath())
                .type(m.getType())
                .title(m.getTitle())
                .summary(m.getSummary())
                .body(m.getBody())
                .status(m.getStatus())
                .pinned(m.isPinned())
                .importance(m.getImportance())
                .sourceType(m.getSourceType())
                .sourceRunId(m.getSourceRunId())
                .contentHash(m.getContentHash())
                .version(m.getVersion() + 1)
                .usageCount(m.getUsageCount() + 1)
                .lastUsedAt(Instant.now())
                .createdAt(m.getCreatedAt())
                .updatedAt(Instant.now())
                .build());
        return true;
    }

    @Override
    public int countActive(String workspaceKey) {
        return (int) store.values().stream()
                .filter(m -> workspaceKey.equals(m.getWorkspaceKey()) && m.getStatus() == MemoryStatus.ACTIVE)
                .count();
    }

    @Override
    public boolean updateStatus(String memoryId, MemoryStatus status, long expectedVersion) {
        AgentMemory existing = store.get(memoryId);
        if (existing == null || existing.getVersion() != expectedVersion) {
            return false;
        }
        AgentMemory updated = AgentMemory.builder()
                .memoryId(existing.getMemoryId())
                .workspaceKey(existing.getWorkspaceKey())
                .workspacePath(existing.getWorkspacePath())
                .type(existing.getType())
                .title(existing.getTitle())
                .summary(existing.getSummary())
                .body(existing.getBody())
                .status(status)
                .pinned(existing.isPinned())
                .importance(existing.getImportance())
                .sourceType(existing.getSourceType())
                .sourceRunId(existing.getSourceRunId())
                .contentHash(existing.getContentHash())
                .version(existing.getVersion() + 1)
                .usageCount(existing.getUsageCount())
                .lastUsedAt(existing.getLastUsedAt())
                .createdAt(existing.getCreatedAt())
                .updatedAt(Instant.now())
                .build();
        store.put(memoryId, updated);
        return true;
    }

    @Override
    public List<AgentMemory> findByContentHash(String workspaceKey, String contentHash) {
        return store.values().stream()
                .filter(m -> workspaceKey.equals(m.getWorkspaceKey())
                        && contentHash.equals(m.getContentHash()))
                .toList();
    }

    @Override
    public List<AgentMemory> findBySourceRunId(String sourceRunId) {
        return store.values().stream()
                .filter(m -> sourceRunId.equals(m.getSourceRunId()))
                .toList();
    }

    @Override
    public List<AgentMemory> findExpiredActive(String workspaceKey, int unusedDays, int minImportance, int limit) {
        Instant cutoff = Instant.now().minus(Duration.ofDays(unusedDays));
        return store.values().stream()
                .filter(m -> workspaceKey.equals(m.getWorkspaceKey())
                        && m.getStatus() == MemoryStatus.ACTIVE
                        && !m.isPinned()
                        && m.getImportance() < minImportance)
                .filter(m -> {
                    Instant lastUsed = m.getLastUsedAt() != null ? m.getLastUsedAt() : m.getCreatedAt();
                    return lastUsed != null && lastUsed.isBefore(cutoff);
                })
                .sorted(Comparator.comparingInt(AgentMemory::getImportance)
                        .thenComparingInt(AgentMemory::getUsageCount)
                        .thenComparing(m -> m.getLastUsedAt() != null ? m.getLastUsedAt() : m.getCreatedAt()))
                .limit(limit)
                .toList();
    }

    @Override
    public List<AgentMemory> findExpiredActiveAll(int unusedDays, int minImportance, int limit) {
        Instant cutoff = Instant.now().minus(Duration.ofDays(unusedDays));
        return store.values().stream()
                .filter(m -> m.getStatus() == MemoryStatus.ACTIVE
                        && !m.isPinned()
                        && m.getImportance() < minImportance)
                .filter(m -> {
                    Instant lastUsed = m.getLastUsedAt() != null ? m.getLastUsedAt() : m.getCreatedAt();
                    return lastUsed != null && lastUsed.isBefore(cutoff);
                })
                .sorted(Comparator.comparingInt(AgentMemory::getImportance)
                        .thenComparingInt(AgentMemory::getUsageCount)
                        .thenComparing(m -> m.getLastUsedAt() != null ? m.getLastUsedAt() : m.getCreatedAt()))
                .limit(limit)
                .toList();
    }

    @Override
    public Optional<AgentMemory> findWeakestCandidate(String workspaceKey) {
        return store.values().stream()
                .filter(m -> workspaceKey.equals(m.getWorkspaceKey())
                        && m.getStatus() == MemoryStatus.ACTIVE
                        && !m.isPinned())
                .min(Comparator.comparingInt(AgentMemory::getImportance)
                        .thenComparingInt(AgentMemory::getUsageCount)
                        .thenComparing(m -> m.getLastUsedAt() != null ? m.getLastUsedAt() : m.getCreatedAt()));
    }

    @Override
    public int batchUpdateStatus(List<String> memoryIds, MemoryStatus status) {
        if (memoryIds == null || memoryIds.isEmpty()) return 0;
        int count = 0;
        for (String id : memoryIds) {
            AgentMemory m = store.get(id);
            if (m != null && m.getStatus() == MemoryStatus.ACTIVE) {
                store.put(id, AgentMemory.builder()
                        .memoryId(m.getMemoryId())
                        .workspaceKey(m.getWorkspaceKey())
                        .workspacePath(m.getWorkspacePath())
                        .type(m.getType())
                        .title(m.getTitle())
                        .summary(m.getSummary())
                        .body(m.getBody())
                        .status(status)
                        .pinned(m.isPinned())
                        .importance(m.getImportance())
                        .sourceType(m.getSourceType())
                        .sourceRunId(m.getSourceRunId())
                        .contentHash(m.getContentHash())
                        .version(m.getVersion() + 1)
                        .usageCount(m.getUsageCount())
                        .lastUsedAt(m.getLastUsedAt())
                        .createdAt(m.getCreatedAt())
                        .updatedAt(Instant.now())
                        .build());
                count++;
            }
        }
        return count;
    }
}
