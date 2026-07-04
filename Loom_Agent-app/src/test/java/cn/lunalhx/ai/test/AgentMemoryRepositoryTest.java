package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemory;
import cn.lunalhx.ai.domain.memory.model.valobj.MemorySourceType;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryStatus;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryType;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentMemoryRepository;
import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

public class AgentMemoryRepositoryTest {

    private AgentMemoryRepository repo;

    @Before
    public void setUp() {
        repo = new InMemoryAgentMemoryRepository();
    }

    @Test
    public void shouldFindByContentHash() {
        repo.save(newMemory("ws-1", "hash-aaa", "run-1"));
        repo.save(newMemory("ws-1", "hash-bbb", "run-2"));
        repo.save(newMemory("ws-2", "hash-aaa", "run-3"));

        List<AgentMemory> results = repo.findByContentHash("ws-1", "hash-aaa");
        assertEquals(1, results.size());
        assertEquals("hash-aaa", results.get(0).getContentHash());
    }

    @Test
    public void shouldFindBySourceRunId() {
        repo.save(newMemory("ws-1", "hash-1", "run-aaa"));
        repo.save(newMemory("ws-1", "hash-2", "run-aaa"));
        repo.save(newMemory("ws-2", "hash-3", "run-bbb"));

        List<AgentMemory> results = repo.findBySourceRunId("run-aaa");
        assertEquals(2, results.size());
    }

    @Test
    public void shouldFindActiveByWorkspace() {
        repo.save(newMemory("ws-1", "hash-1", "run-1"));
        repo.save(newMemory("ws-1", "hash-2", "run-2"));
        repo.save(newMemory("ws-2", "hash-3", "run-3"));

        List<AgentMemory> active = repo.findActive("ws-1", 10);
        assertEquals(2, active.size());
    }

    @Test
    public void shouldUpdateStatus() {
        AgentMemory saved = repo.save(newMemory("ws-1", "hash-1", "run-1"));
        long version = saved.getVersion();

        assertTrue(repo.updateStatus(saved.getMemoryId(), MemoryStatus.ARCHIVED, version));
        assertFalse("wrong version should fail", repo.updateStatus(saved.getMemoryId(), MemoryStatus.DELETED, version));
    }

    @Test
    public void shouldSearchByKeywords() {
        repo.save(newMemoryWithText("ws-1", "hash-1", "run-1", "Java", "Java programming", "Java details"));
        repo.save(newMemoryWithText("ws-1", "hash-2", "run-2", "Python", "Python scripting", "Python details"));

        List<AgentMemory> results = repo.searchByKeywords("ws-1", List.of("java"), 10);
        assertEquals(1, results.size());
        assertEquals("Java", results.get(0).getTitle());
    }

    private AgentMemory newMemory(String workspaceKey, String contentHash, String sourceRunId) {
        return AgentMemory.builder()
                .memoryId(UUID.randomUUID().toString())
                .workspaceKey(workspaceKey)
                .workspacePath("/tmp/test")
                .type(MemoryType.PREFERENCE)
                .title("Test Title")
                .summary("Test Summary")
                .body("Test Body")
                .status(MemoryStatus.ACTIVE)
                .pinned(false)
                .importance(50)
                .sourceType(MemorySourceType.AUTO_EXTRACTION)
                .sourceRunId(sourceRunId)
                .contentHash(contentHash)
                .version(0)
                .usageCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private AgentMemory newMemoryWithText(String workspaceKey, String contentHash, String sourceRunId,
                                           String title, String summary, String body) {
        return AgentMemory.builder()
                .memoryId(UUID.randomUUID().toString())
                .workspaceKey(workspaceKey)
                .workspacePath("/tmp/test")
                .type(MemoryType.PREFERENCE)
                .title(title)
                .summary(summary)
                .body(body)
                .status(MemoryStatus.ACTIVE)
                .pinned(false)
                .importance(50)
                .sourceType(MemorySourceType.AUTO_EXTRACTION)
                .sourceRunId(sourceRunId)
                .contentHash(contentHash)
                .version(0)
                .usageCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
