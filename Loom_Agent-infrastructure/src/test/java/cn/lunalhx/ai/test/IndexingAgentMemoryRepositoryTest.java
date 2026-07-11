package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryVectorIndex;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemory;
import cn.lunalhx.ai.domain.memory.model.valobj.MemorySourceType;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryStatus;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryType;
import cn.lunalhx.ai.infrastructure.adapter.repository.IndexingAgentMemoryRepository;
import cn.lunalhx.ai.infrastructure.dao.AgentMemoryEmbeddingJobDao;
import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class IndexingAgentMemoryRepositoryTest {

    private AgentMemoryRepository delegate;
    private AgentMemoryVectorIndex vectorIndex;
    private AgentMemoryEmbeddingJobDao embeddingJobDao;
    private IndexingAgentMemoryRepository repo;

    @Before
    public void setUp() {
        delegate = mock(AgentMemoryRepository.class);
        vectorIndex = mock(AgentMemoryVectorIndex.class);
        embeddingJobDao = mock(AgentMemoryEmbeddingJobDao.class);
        repo = new IndexingAgentMemoryRepository(delegate, vectorIndex, embeddingJobDao);
    }

    @Test
    public void shouldDelegateSaveAndEnqueueWhenVectorAvailable() {
        when(vectorIndex.available()).thenReturn(true);
        AgentMemory mem = createMemory("m1", "test");
        when(delegate.save(mem)).thenReturn(mem);

        AgentMemory result = repo.save(mem);
        assertEquals("m1", result.getMemoryId());
        verify(delegate).save(mem);
        verify(embeddingJobDao).insertOrIgnore(anyString(), anyString(), eq("UPSERT"));
    }

    @Test
    public void shouldDelegateSaveWithoutEnqueueWhenVectorUnavailable() {
        when(vectorIndex.available()).thenReturn(false);
        AgentMemory mem = createMemory("m1", "test");
        when(delegate.save(mem)).thenReturn(mem);

        repo.save(mem);
        verify(delegate).save(mem);
        verify(embeddingJobDao, never()).insertOrIgnore(anyString(), anyString(), anyString());
    }

    @Test
    public void shouldEnqueueDeleteOnArchive() {
        when(vectorIndex.available()).thenReturn(true);
        when(delegate.updateStatus("m1", MemoryStatus.ARCHIVED, 1L)).thenReturn(true);

        boolean result = repo.updateStatus("m1", MemoryStatus.ARCHIVED, 1L);
        assertTrue(result);
        verify(delegate).updateStatus("m1", MemoryStatus.ARCHIVED, 1L);
        verify(embeddingJobDao).insertOrIgnore(anyString(), anyString(), eq("DELETE"));
    }

    @Test
    public void shouldEnqueueDeleteOnDeleted() {
        when(vectorIndex.available()).thenReturn(true);
        when(delegate.updateStatus("m1", MemoryStatus.DELETED, 1L)).thenReturn(true);

        boolean result = repo.updateStatus("m1", MemoryStatus.DELETED, 1L);
        assertTrue(result);
        verify(embeddingJobDao).insertOrIgnore(anyString(), anyString(), eq("DELETE"));
    }

    @Test
    public void shouldNotEnqueueWhenUpdateStatusFails() {
        when(vectorIndex.available()).thenReturn(true);
        when(delegate.updateStatus("m1", MemoryStatus.ARCHIVED, 1L)).thenReturn(false);

        boolean result = repo.updateStatus("m1", MemoryStatus.ARCHIVED, 1L);
        assertFalse(result);
        verify(delegate).updateStatus("m1", MemoryStatus.ARCHIVED, 1L);
        verify(embeddingJobDao, never()).insertOrIgnore(anyString(), anyString(), anyString());
    }

    @Test
    public void shouldEnqueueUpsertOnActivate() {
        when(vectorIndex.available()).thenReturn(true);
        when(delegate.updateStatus("m1", MemoryStatus.ACTIVE, 1L)).thenReturn(true);
        AgentMemory mem = createMemory("m1", "test");
        when(delegate.findById("m1")).thenReturn(Optional.of(mem));

        boolean result = repo.updateStatus("m1", MemoryStatus.ACTIVE, 1L);
        assertTrue(result);
        verify(delegate).findById("m1");
        verify(embeddingJobDao).insertOrIgnore(anyString(), anyString(), eq("UPSERT"));
    }

    @Test
    public void shouldDelegateReadMethodsDirectly() {
        repo.findById("m1");
        verify(delegate).findById("m1");

        repo.findActive("ws1", 10);
        verify(delegate).findActive("ws1", 10);

        repo.findPinned("ws1", 5);
        verify(delegate).findPinned("ws1", 5);

        List<String> keywords = List.of("java");
        repo.searchByKeywords("ws1", keywords, 10);
        verify(delegate).searchByKeywords("ws1", keywords, 10);

        repo.updateUsage("m1", 2L);
        verify(delegate).updateUsage("m1", 2L);

        repo.countActive("ws1");
        verify(delegate).countActive("ws1");

        repo.findByContentHash("ws1", "hash");
        verify(delegate).findByContentHash("ws1", "hash");

        repo.findBySourceRunId("run1");
        verify(delegate).findBySourceRunId("run1");
    }

    private AgentMemory createMemory(String id, String title) {
        return AgentMemory.builder()
                .memoryId(id)
                .workspaceKey("ws1")
                .type(MemoryType.PREFERENCE)
                .title(title)
                .summary("summary")
                .body("body")
                .status(MemoryStatus.ACTIVE)
                .importance(50)
                .pinned(false)
                .sourceType(MemorySourceType.MANUAL_API)
                .version(1)
                .usageCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
