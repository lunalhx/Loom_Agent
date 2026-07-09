package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryVectorIndex;
import cn.lunalhx.ai.domain.memory.adapter.port.MemoryEmbeddingGateway;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemory;
import cn.lunalhx.ai.domain.memory.model.valobj.EmbeddingVector;
import cn.lunalhx.ai.domain.memory.model.valobj.MemorySearchHit;
import cn.lunalhx.ai.domain.memory.model.valobj.MemorySourceType;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryStatus;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryType;
import cn.lunalhx.ai.domain.memory.model.valobj.ScoredMemoryId;
import cn.lunalhx.ai.domain.memory.service.MemorySearchService;
import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class MemorySearchServiceTest {

    private AgentMemoryRepository memoryRepository;
    private AgentMemoryVectorIndex vectorIndex;
    private MemoryEmbeddingGateway embeddingGateway;
    private MemorySearchService searchService;

    @Before
    public void setUp() {
        memoryRepository = mock(AgentMemoryRepository.class);
        vectorIndex = mock(AgentMemoryVectorIndex.class);
        embeddingGateway = mock(MemoryEmbeddingGateway.class);
        searchService = new MemorySearchService(memoryRepository, vectorIndex, embeddingGateway, 50);
    }

    private static List<AgentMemory> extract(List<MemorySearchHit> hits) {
        return hits.stream().map(MemorySearchHit::memory).collect(Collectors.toList());
    }

    @Test
    public void shouldFallbackToKeywordWhenVectorUnavailable() {
        when(vectorIndex.available()).thenReturn(false);
        when(memoryRepository.searchByKeywords(eq("ws1"), anyList(), eq(10)))
                .thenReturn(List.of(createMemory("m1", "JUnit testing")));

        List<AgentMemory> results = extract(searchService.search("ws1", "testing", 10));
        assertEquals(1, results.size());
        assertEquals("m1", results.get(0).getMemoryId());
        verify(embeddingGateway, never()).embed(anyString());
    }

    @Test
    public void shouldUseVectorSearchWhenAvailable() {
        when(vectorIndex.available()).thenReturn(true);
        EmbeddingVector queryVec = new EmbeddingVector(new float[]{0.1f, 0.2f}, "test-model", 2);
        when(embeddingGateway.embed(anyString())).thenReturn(queryVec);

        ScoredMemoryId scored = new ScoredMemoryId(1L, "m1", 0.1);
        when(vectorIndex.search(eq("ws1"), eq(queryVec), eq(50))).thenReturn(List.of(scored));

        AgentMemory memory = createMemory("m1", "Test memory");
        when(memoryRepository.findById("m1")).thenReturn(Optional.of(memory));

        List<AgentMemory> results = extract(searchService.search("ws1", "test query", 5));
        assertEquals(1, results.size());
        verify(embeddingGateway).embed(anyString());
    }

    @Test
    public void shouldFallbackOnVectorSearchError() {
        when(vectorIndex.available()).thenReturn(true);
        when(embeddingGateway.embed(anyString())).thenThrow(new RuntimeException("API error"));
        when(memoryRepository.searchByKeywords(eq("ws1"), anyList(), eq(5)))
                .thenReturn(List.of(createMemory("m1", "fallback")));

        List<AgentMemory> results = extract(searchService.search("ws1", "test", 5));
        assertEquals(1, results.size());
        assertEquals("m1", results.get(0).getMemoryId());
    }

    @Test
    public void shouldLimitResultsToRequestedCount() {
        when(vectorIndex.available()).thenReturn(false);
        AgentMemory m1 = createMemory("m1", "first");
        AgentMemory m2 = createMemory("m2", "second");
        AgentMemory m3 = createMemory("m3", "third");
        when(memoryRepository.searchByKeywords(eq("ws1"), anyList(), eq(2)))
                .thenReturn(List.of(m1, m2));

        List<AgentMemory> results = extract(searchService.search("ws1", "test query", 2));
        assertEquals(2, results.size());
    }

    @Test
    public void shouldReturnEmptyOnNoResults() {
        when(vectorIndex.available()).thenReturn(true);
        EmbeddingVector queryVec = new EmbeddingVector(new float[]{0.1f, 0.2f}, "test-model", 2);
        when(embeddingGateway.embed(anyString())).thenReturn(queryVec);
        when(vectorIndex.search(eq("ws1"), eq(queryVec), eq(50))).thenReturn(List.of());

        List<MemorySearchHit> results = searchService.search("ws1", "test query", 5);
        assertTrue(results.isEmpty());

        when(vectorIndex.available()).thenReturn(false);
        when(memoryRepository.searchByKeywords(eq("ws1"), anyList(), eq(5)))
                .thenReturn(List.of());

        results = searchService.search("ws1", "test", 5);
        assertTrue(results.isEmpty());
    }

    @Test
    public void shouldRerankByVectorDistance() {
        when(vectorIndex.available()).thenReturn(true);
        EmbeddingVector queryVec = new EmbeddingVector(new float[]{0.1f}, "test-model", 1);
        when(embeddingGateway.embed(anyString())).thenReturn(queryVec);

        ScoredMemoryId scored1 = new ScoredMemoryId(1L, "m1", 0.1);
        ScoredMemoryId scored2 = new ScoredMemoryId(2L, "m2", 0.9);
        when(vectorIndex.search(eq("ws1"), eq(queryVec), eq(50)))
                .thenReturn(List.of(scored1, scored2));

        AgentMemory mem1 = createMemory("m1", "memory one");
        AgentMemory mem2 = createMemory("m2", "memory two");
        when(memoryRepository.findById("m1")).thenReturn(Optional.of(mem1));
        when(memoryRepository.findById("m2")).thenReturn(Optional.of(mem2));

        List<AgentMemory> results = extract(searchService.search("ws1", "simple query", 10));
        assertEquals(2, results.size());
        assertEquals("m1", results.get(0).getMemoryId());
        assertEquals("m2", results.get(1).getMemoryId());
    }

    @Test
    public void shouldRespectPinnedOrderInRealScenario() {
        when(vectorIndex.available()).thenReturn(false);
        AgentMemory pinned = createMemory("p1", "pinned memory");
        pinned.setPinned(true);
        when(memoryRepository.searchByKeywords(eq("ws1"), anyList(), eq(10)))
                .thenReturn(List.of(pinned));

        List<MemorySearchHit> hits = searchService.search("ws1", "test", 10);
        assertEquals(1, hits.size());
        assertEquals("p1", hits.get(0).memory().getMemoryId());
        assertTrue(hits.get(0).memory().isPinned());
    }

    @Test
    public void shouldHandleEmptyQuery() {
        when(vectorIndex.available()).thenReturn(false);
        AgentMemory mem = createMemory("m1", "active memory");
        when(memoryRepository.findActive("ws1", 10)).thenReturn(List.of(mem));

        List<AgentMemory> results = extract(searchService.search("ws1", "", 10));
        assertEquals(1, results.size());
        verify(memoryRepository).findActive("ws1", 10);

        results = extract(searchService.search("ws1", null, 10));
        assertEquals(1, results.size());
    }

    @Test
    public void shouldIncludeScoreAndSourceInHits() {
        when(vectorIndex.available()).thenReturn(false);
        AgentMemory mem = createMemory("m1", "test memory");
        when(memoryRepository.searchByKeywords(eq("ws1"), anyList(), eq(5)))
                .thenReturn(List.of(mem));

        List<MemorySearchHit> hits = searchService.search("ws1", "test", 5);
        assertEquals(1, hits.size());
        assertEquals(MemorySearchHit.SOURCE_KEYWORD, hits.get(0).recallSource());
        assertTrue(hits.get(0).comprehensiveScore() >= 0.0);
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
