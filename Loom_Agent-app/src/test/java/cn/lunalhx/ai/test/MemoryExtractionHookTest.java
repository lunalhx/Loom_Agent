package cn.lunalhx.ai.test;

import cn.lunalhx.ai.config.MemoryProperties;
import cn.lunalhx.ai.domain.agent.adapter.port.ConversationDeletionRepository;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookContext;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookEvent;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationDeletion;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemory;
import cn.lunalhx.ai.domain.memory.model.entity.MemoryExtractionPayload;
import cn.lunalhx.ai.domain.memory.service.MemoryExtractionService;
import cn.lunalhx.ai.domain.memory.service.MemoryExtractionService.ExtractedMemory;
import cn.lunalhx.ai.domain.memory.service.MemoryExtractionService.ExtractionResult;
import cn.lunalhx.ai.domain.memory.service.MemoryPersistenceService;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryType;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentMemoryRepository;
import cn.lunalhx.ai.runtime.hook.MemoryExtractionHook;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

public class MemoryExtractionHookTest {

    private MemoryProperties properties;
    private MemoryExtractionHook hook;
    private FakeExtractionService extractionService;
    private AgentMemoryRepository memoryRepo;
    private MemoryPersistenceService persistenceService;
    private FakeDeletionRepository deletionRepository;

    @Before
    public void setUp() {
        properties = new MemoryProperties();
        properties.setEnabled(true);
        properties.setGenerateMemories(true);
        properties.setExtractionTimeoutSeconds(15);
        properties.setMaxActive(200);

        extractionService = new FakeExtractionService();
        memoryRepo = new InMemoryAgentMemoryRepository(200);
        persistenceService = new MemoryPersistenceService(memoryRepo, properties.getMaxActive());
        deletionRepository = new FakeDeletionRepository();

        hook = new MemoryExtractionHook(extractionService, persistenceService, properties, deletionRepository);
    }

    @Test
    public void shouldExtractAndPersistOnAfterStop() {
        extractionService.setResult(ExtractionResult.success(List.of(
                new ExtractedMemory(MemoryType.PREFERENCE, "Uses tabs", "S", "Prefers tabs.", 80, "hash-1")
        )));

        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-hook-1");
        ctx.setQuestion("What is Java?");
        ctx.setFinalAnswer("Java is a programming language.");
        ctx.setStep(5);
        ctx.setResolvedWorkspace(Path.of("/tmp/test"));

        AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();

        AgentHookResult result = hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);
        assertNotNull(result);

        List<AgentMemory> memories = memoryRepo.findBySourceRunId("run-hook-1");
        assertEquals(1, memories.size());
        assertEquals("Uses tabs", memories.get(0).getTitle());
        assertTrue(extractionService.lastPayload != null);
        assertEquals("What is Java?", extractionService.lastPayload.getQuestion());
        assertSame(ctx, extractionService.lastContext);
    }

    @Test
    public void shouldAlwaysProceedAndNeverChangeStatus() {
        extractionService.setResult(ExtractionResult.success(List.of(
                new ExtractedMemory(MemoryType.PREFERENCE, "T", "S", "B", 50, "h")
        )));

        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-proceed");
        ctx.setFinalAnswer("answer");
        ctx.setResolvedWorkspace(Path.of("/tmp"));

        AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();
        AgentHookResult result = hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);

        assertNotNull(result);
        assertEquals(cn.lunalhx.ai.domain.agent.flow.hook.AgentHookAction.NONE, result.getAction());
    }

    @Test
    public void shouldNotExtractForNonAfterStopEvents() {
        extractionService.setResult(ExtractionResult.success(List.of(
                new ExtractedMemory(MemoryType.PREFERENCE, "T", "S", "B", 50, "h")
        )));

        for (AgentHookEvent event : AgentHookEvent.values()) {
            if (event == AgentHookEvent.AFTER_STOP) continue;

            AgentContext ctx = new AgentContext();
            ctx.setRunId("run-" + event.name());
            ctx.setFinalAnswer("Some answer");

            AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();
            hook.onEvent(event, hookCtx);

            assertTrue(memoryRepo.findBySourceRunId("run-" + event.name()).isEmpty());
        }
        assertNull(extractionService.lastPayload);
    }

    @Test
    public void shouldNotExtractForSubAgent() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-sub");
        ctx.setParentRunId("parent-run");
        ctx.setFinalAnswer("Sub agent answer");

        AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();
        hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);

        assertTrue(memoryRepo.findBySourceRunId("run-sub").isEmpty());
        assertNull(extractionService.lastPayload);
    }

    @Test
    public void shouldNotExtractForEmptyFinalAnswer() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-empty");
        ctx.setFinalAnswer("");

        AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();
        hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);

        assertTrue(memoryRepo.findBySourceRunId("run-empty").isEmpty());
        assertNull(extractionService.lastPayload);
    }

    @Test
    public void shouldNotExtractForNullFinalAnswer() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-null");
        ctx.setFinalAnswer(null);

        AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();
        hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);

        assertTrue(memoryRepo.findBySourceRunId("run-null").isEmpty());
        assertNull(extractionService.lastPayload);
    }

    @Test
    public void shouldNotExtractWhenDisabled() {
        properties.setEnabled(false);

        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-off");
        ctx.setFinalAnswer("answer");

        AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();
        hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);

        assertTrue(memoryRepo.findBySourceRunId("run-off").isEmpty());
        assertNull(extractionService.lastPayload);
    }

    @Test
    public void shouldNotExtractWhenGenerateMemoriesDisabled() {
        properties.setGenerateMemories(false);

        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-no-gen");
        ctx.setFinalAnswer("answer");

        AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();
        hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);

        assertTrue(memoryRepo.findBySourceRunId("run-no-gen").isEmpty());
        assertNull(extractionService.lastPayload);
    }

    @Test
    public void shouldHandleEmptyExtractionResult() {
        extractionService.setResult(ExtractionResult.empty());

        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-empty-result");
        ctx.setFinalAnswer("answer");
        ctx.setResolvedWorkspace(Path.of("/tmp"));

        AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();
        AgentHookResult result = hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);

        assertNotNull(result);
        assertTrue(memoryRepo.findBySourceRunId("run-empty-result").isEmpty());
    }

    @Test
    public void shouldHandleRetryableErrorGracefully() {
        extractionService.setResult(ExtractionResult.retryable("API unavailable"));

        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-retryable");
        ctx.setFinalAnswer("answer");
        ctx.setResolvedWorkspace(Path.of("/tmp"));

        AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();
        AgentHookResult result = hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);

        assertNotNull(result);
        assertTrue(memoryRepo.findBySourceRunId("run-retryable").isEmpty());
    }

    @Test
    public void shouldHandleExtractionExceptionGracefully() {
        extractionService.setException(new RuntimeException("boom"));

        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-ex");
        ctx.setFinalAnswer("answer");
        ctx.setResolvedWorkspace(Path.of("/tmp"));

        AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();
        AgentHookResult result = hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);

        assertNotNull(result);
        assertTrue(memoryRepo.findBySourceRunId("run-ex").isEmpty());
    }

    @Test
    public void shouldSkipExtractionWhenConversationBeingDeleted() {
        deletionRepository.setDeletion(new ConversationDeletion("conv-1", "PENDING", Instant.now(), null, null, 0, null));

        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-deleted");
        ctx.setConversationId("conv-1");
        ctx.setFinalAnswer("answer");
        ctx.setResolvedWorkspace(Path.of("/tmp"));

        AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();
        hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);

        assertNull(extractionService.lastPayload);
    }

    @Test
    public void shouldSkipPersistenceWhenConversationDeletedDuringExtraction() {
        extractionService.setResult(ExtractionResult.success(List.of(
                new ExtractedMemory(MemoryType.PREFERENCE, "T", "S", "B", 50, "h")
        )));
        // deletion will appear after extraction is "done"
        extractionService.onExtract = () -> deletionRepository.setDeletion(
                new ConversationDeletion("conv-during", "PENDING", Instant.now(), null, null, 0, null));

        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-during");
        ctx.setConversationId("conv-during");
        ctx.setFinalAnswer("answer");
        ctx.setResolvedWorkspace(Path.of("/tmp"));

        AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();
        hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);

        assertTrue(memoryRepo.findBySourceRunId("run-during").isEmpty());
    }

    @Test
    public void shouldHandleNullContext() {
        AgentHookContext hookCtx = AgentHookContext.builder().build();
        AgentHookResult result = hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);
        assertNotNull(result);
        assertNull(extractionService.lastPayload);
    }

    @Test
    public void shouldHandleNullDeletionRepository() {
        hook = new MemoryExtractionHook(extractionService, persistenceService, properties, null);

        extractionService.setResult(ExtractionResult.success(List.of(
                new ExtractedMemory(MemoryType.PREFERENCE, "T", "S", "B", 50, "h")
        )));

        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-null-del");
        ctx.setConversationId("conv-null-del");
        ctx.setFinalAnswer("answer");
        ctx.setResolvedWorkspace(Path.of("/tmp"));

        AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();
        hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);

        List<AgentMemory> memories = memoryRepo.findBySourceRunId("run-null-del");
        assertEquals(1, memories.size());
    }

    @Test
    public void shouldDeduplicateBySourceRunId() {
        extractionService.setResult(ExtractionResult.success(List.of(
                new ExtractedMemory(MemoryType.PREFERENCE, "T", "S", "B", 50, "h")
        )));

        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-once");
        ctx.setQuestion("q");
        ctx.setFinalAnswer("a");
        ctx.setStep(1);
        ctx.setResolvedWorkspace(Path.of("/tmp"));

        AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();

        hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);
        hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);

        List<AgentMemory> memories = memoryRepo.findBySourceRunId("run-once");
        assertEquals(1, memories.size());
    }

    @Test
    public void shouldPassRealAgentContextForTracing() {
        extractionService.setResult(ExtractionResult.empty());

        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-trace");
        ctx.setTraceId("trace-123");
        ctx.setFinalAnswer("answer");
        ctx.setResolvedWorkspace(Path.of("/tmp"));

        AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();
        hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);

        assertSame(ctx, extractionService.lastContext);
    }

    // ===== fakes =====

    private static class FakeExtractionService extends MemoryExtractionService {
        MemoryExtractionPayload lastPayload;
        AgentContext lastContext;
        ExtractionResult result;
        RuntimeException error;
        Runnable onExtract;

        FakeExtractionService() {
            super(null, null, null, null);
        }

        void setResult(ExtractionResult r) {
            this.result = r;
            this.error = null;
        }

        void setException(RuntimeException e) {
            this.error = e;
            this.result = null;
        }

        @Override
        public ExtractionResult extract(MemoryExtractionPayload payload, long deadlineEpochMs) {
            return extract(payload, deadlineEpochMs, null);
        }

        @Override
        public ExtractionResult extract(MemoryExtractionPayload payload, long deadlineEpochMs,
                                         AgentContext agentContext) {
            this.lastPayload = payload;
            this.lastContext = agentContext;
            if (onExtract != null) {
                onExtract.run();
            }
            if (error != null) {
                throw error;
            }
            return result == null ? ExtractionResult.empty() : result;
        }
    }

    private static class FakeDeletionRepository implements ConversationDeletionRepository {
        private ConversationDeletion deletion;

        void setDeletion(ConversationDeletion d) {
            this.deletion = d;
        }

        @Override
        public ConversationDeletion save(ConversationDeletion deletion) {
            this.deletion = deletion;
            return deletion;
        }

        @Override
        public Optional<ConversationDeletion> find(String conversationId) {
            return Optional.ofNullable(deletion);
        }

        @Override
        public List<ConversationDeletion> findPendingWork() {
            return Collections.emptyList();
        }

        @Override
        public boolean claimTask(String conversationId, String lockedBy, String lockExpiresAt) {
            return false;
        }

        @Override
        public boolean updateStatus(String conversationId, String status, int retryCount, String lastError) {
            return false;
        }

        @Override
        public boolean updateStatusAndReleaseLock(String conversationId, String status, int retryCount, String lastError) {
            return false;
        }

        @Override
        public boolean markCompleted(String conversationId) {
            return false;
        }

        @Override
        public boolean resetForRetry(String conversationId) {
            return false;
        }

        @Override
        public void releaseLock(String conversationId) {
        }

        @Override
        public List<ConversationDeletion> findStaleTasks(String staleThreshold) {
            return Collections.emptyList();
        }
    }
}
