package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemory;
import cn.lunalhx.ai.domain.memory.model.valobj.MemorySourceType;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryStatus;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryType;
import cn.lunalhx.ai.infrastructure.dao.AgentMemoryDao;
import cn.lunalhx.ai.infrastructure.dao.po.AgentMemoryPO;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class MybatisAgentMemoryRepository implements AgentMemoryRepository {

    private final AgentMemoryDao dao;

    public MybatisAgentMemoryRepository(AgentMemoryDao dao) {
        this.dao = dao;
    }

    @Override
    public AgentMemory save(AgentMemory memory) {
        AgentMemoryPO po = toPo(memory);
        AgentMemoryPO existing = dao.selectById(memory.getMemoryId());
        if (existing == null) {
            po.setMemoryId(memory.getMemoryId() != null ? memory.getMemoryId() : UUID.randomUUID().toString());
            po.setVersion(1L);
            dao.insert(po);
        } else {
            int rows = dao.update(po);
            if (rows == 0) {
                throw new IllegalStateException("Optimistic lock conflict for memory " + memory.getMemoryId());
            }
        }
        return toEntity(dao.selectById(po.getMemoryId()));
    }

    @Override
    public Optional<AgentMemory> findById(String memoryId) {
        return Optional.ofNullable(dao.selectById(memoryId)).map(this::toEntity);
    }

    @Override
    public List<AgentMemory> findActive(String workspaceKey, int limit) {
        return dao.selectActive(workspaceKey, limit).stream()
                .map(this::toEntity)
                .toList();
    }

    @Override
    public List<AgentMemory> findPinned(String workspaceKey, int limit) {
        return dao.selectPinned(workspaceKey, limit).stream()
                .map(this::toEntity)
                .toList();
    }

    @Override
    public List<AgentMemory> searchByKeywords(String workspaceKey, List<String> keywords, int limit) {
        String searchTerm = String.join(" ", keywords);
        if (searchTerm.isBlank()) {
            return Collections.emptyList();
        }
        return dao.searchByKeywords(workspaceKey, searchTerm, limit).stream()
                .map(this::toEntity)
                .toList();
    }

    @Override
    public boolean updateUsage(String memoryId, long newVersion) {
        return dao.updateUsage(memoryId, newVersion) > 0;
    }

    @Override
    public int countActive(String workspaceKey) {
        return dao.countActive(workspaceKey);
    }

    @Override
    public boolean updateStatus(String memoryId, MemoryStatus status, long expectedVersion) {
        return dao.updateStatus(memoryId, status.name(), expectedVersion) > 0;
    }

    @Override
    public List<AgentMemory> findByContentHash(String workspaceKey, String contentHash) {
        return dao.selectByContentHash(workspaceKey, contentHash).stream()
                .map(this::toEntity)
                .toList();
    }

    @Override
    public List<AgentMemory> findBySourceRunId(String sourceRunId) {
        return dao.selectBySourceRunId(sourceRunId).stream()
                .map(this::toEntity)
                .toList();
    }

    private AgentMemoryPO toPo(AgentMemory m) {
        AgentMemoryPO po = new AgentMemoryPO();
        po.setMemoryId(m.getMemoryId());
        po.setWorkspaceKey(m.getWorkspaceKey());
        po.setWorkspacePath(m.getWorkspacePath());
        po.setType(m.getType().name());
        po.setTitle(m.getTitle());
        po.setSummary(m.getSummary() != null ? m.getSummary() : "");
        po.setBody(m.getBody() != null ? m.getBody() : "");
        po.setStatus(m.getStatus().name());
        po.setPinned(m.isPinned() ? 1 : 0);
        po.setImportance(m.getImportance());
        po.setSourceType(m.getSourceType().name());
        po.setSourceRunId(m.getSourceRunId());
        po.setContentHash(m.getContentHash() != null ? m.getContentHash() : "");
        po.setVersion(m.getVersion());
        po.setUsageCount(m.getUsageCount());
        po.setLastUsedAt(m.getLastUsedAt() != null ? m.getLastUsedAt().toString() : null);
        po.setCreatedAt(m.getCreatedAt() != null ? m.getCreatedAt().toString() : null);
        po.setUpdatedAt(m.getUpdatedAt() != null ? m.getUpdatedAt().toString() : null);
        return po;
    }

    private AgentMemory toEntity(AgentMemoryPO po) {
        DateTimeFormatter fmt = DateTimeFormatter.ISO_INSTANT;
        return AgentMemory.builder()
                .memoryId(po.getMemoryId())
                .workspaceKey(po.getWorkspaceKey())
                .workspacePath(po.getWorkspacePath())
                .type(MemoryType.valueOf(po.getType()))
                .title(po.getTitle())
                .summary(po.getSummary())
                .body(po.getBody())
                .status(MemoryStatus.valueOf(po.getStatus()))
                .pinned(po.getPinned() != null && po.getPinned() == 1)
                .importance(po.getImportance() != null ? po.getImportance() : 50)
                .sourceType(MemorySourceType.valueOf(po.getSourceType()))
                .sourceRunId(po.getSourceRunId())
                .contentHash(po.getContentHash())
                .version(po.getVersion() != null ? po.getVersion() : 1)
                .usageCount(po.getUsageCount() != null ? po.getUsageCount() : 0)
                .lastUsedAt(po.getLastUsedAt() != null ? Instant.from(fmt.parse(po.getLastUsedAt())) : null)
                .createdAt(po.getCreatedAt() != null ? Instant.from(fmt.parse(po.getCreatedAt())) : null)
                .updatedAt(po.getUpdatedAt() != null ? Instant.from(fmt.parse(po.getUpdatedAt())) : null)
                .build();
    }
}
