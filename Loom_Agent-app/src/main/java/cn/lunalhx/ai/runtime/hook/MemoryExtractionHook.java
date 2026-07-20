package cn.lunalhx.ai.runtime.hook;

import cn.lunalhx.ai.config.MemoryProperties;
import cn.lunalhx.ai.domain.agent.adapter.port.ConversationDeletionRepository;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHook;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookContext;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookEvent;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.MemoryRuntimeProperties;
import cn.lunalhx.ai.domain.memory.model.entity.MemoryExtractionPayload;
import cn.lunalhx.ai.domain.memory.service.MemoryExtractionService;
import cn.lunalhx.ai.domain.memory.service.MemoryExtractionService.ExtractionResult;
import cn.lunalhx.ai.domain.memory.service.MemoryPersistenceService;
import cn.lunalhx.ai.domain.memory.service.WorkspaceKeyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@Order(700)
@ConditionalOnProperty(name = "loom.agent.long-term-memory.enabled", havingValue = "true")
public class MemoryExtractionHook implements AgentHook {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionHook.class);

    private final MemoryExtractionService extractionService;
    private final MemoryPersistenceService persistenceService;
    private final MemoryProperties memoryProperties;
    private final ConversationDeletionRepository deletionRepository;
    private final ExecutorService extractionExecutor;

    public MemoryExtractionHook(MemoryExtractionService extractionService,
                                 MemoryPersistenceService persistenceService,
                                 MemoryProperties memoryProperties) {
        this(extractionService, persistenceService, memoryProperties, null, daemonExecutor());
    }

    public MemoryExtractionHook(MemoryExtractionService extractionService,
                                 MemoryPersistenceService persistenceService,
                                 MemoryProperties memoryProperties,
                                 ConversationDeletionRepository deletionRepository) {
        this(extractionService, persistenceService, memoryProperties,
                deletionRepository, daemonExecutor());
    }

    @Autowired
    public MemoryExtractionHook(MemoryExtractionService extractionService,
                                 MemoryPersistenceService persistenceService,
                                 MemoryProperties memoryProperties,
                                 ConversationDeletionRepository deletionRepository,
                                 @org.springframework.beans.factory.annotation.Qualifier("memoryExtractionExecutor")
                                 ExecutorService extractionExecutor) {
        this.extractionService = extractionService;
        this.persistenceService = persistenceService;
        this.memoryProperties = memoryProperties;
        this.deletionRepository = deletionRepository;
        this.extractionExecutor = extractionExecutor;
    }

    @Override
    public AgentHookResult onEvent(AgentHookEvent event, AgentHookContext context) {
        if (event != AgentHookEvent.AFTER_STOP) {
            return AgentHookResult.proceed();
        }

        if (!memoryProperties.isEnabled()) {
            return AgentHookResult.proceed();
        }

        AgentContext agentContext = context.getAgentContext();
        if (agentContext == null) {
            return AgentHookResult.proceed();
        }
        MemoryRuntimeProperties runMemory = agentContext.memoryRuntimeProperties(memoryProperties);
        if (!runMemory.isGenerateMemories()) {
            return AgentHookResult.proceed();
        }

        if (agentContext.getParentRunId() != null && !agentContext.getParentRunId().isEmpty()) {
            return AgentHookResult.proceed();
        }

        if (agentContext.getFinalAnswer() == null || agentContext.getFinalAnswer().isBlank()) {
            return AgentHookResult.proceed();
        }

        String conversationId = agentContext.getConversationId();
        if (isBeingDeleted(conversationId)) {
            log.debug("Skip memory extraction: conversation {} is being deleted", conversationId);
            return AgentHookResult.proceed();
        }

        String workspacePath = agentContext.getResolvedWorkspace() != null
                ? agentContext.getResolvedWorkspace().toString()
                : "";
        String workspaceKey = WorkspaceKeyUtil.compute(workspacePath);
        String sourceRunId = agentContext.getRunId();

        MemoryExtractionPayload payload = new MemoryExtractionPayload(
                agentContext.getQuestion(),
                agentContext.getFinalAnswer(),
                agentContext.getStep(),
                workspacePath);

        long deadlineEpochMs = System.currentTimeMillis()
                + runMemory.getExtractionTimeoutSeconds() * 1000L;

        ExtractionResult result;
        try {
            result = extractWithTimeout(payload, deadlineEpochMs, agentContext, runMemory);
        } catch (TimeoutException e) {
            log.warn("Memory extraction timed out after {}s for run={}",
                    runMemory.getExtractionTimeoutSeconds(), sourceRunId);
            return AgentHookResult.proceed();
        } catch (Exception e) {
            log.warn("Memory extraction failed for run={}: {}", sourceRunId, e.getMessage());
            return AgentHookResult.proceed();
        }

        if (result == null) {
            return AgentHookResult.proceed();
        }

        if (result.retryable()) {
            log.warn("Memory extraction retryable error for run={}: {}", sourceRunId, result.errorMessage());
            return AgentHookResult.proceed();
        }

        if (result.isEmpty()) {
            log.info("Memory extraction produced no memories for run={}", sourceRunId);
            return AgentHookResult.proceed();
        }

        if (isBeingDeleted(conversationId)) {
            log.debug("Skip memory persistence: conversation {} was deleted during extraction", conversationId);
            return AgentHookResult.proceed();
        }

        try {
            var saved = persistenceService.persist(payload, result.memories(), sourceRunId,
                    workspaceKey, runMemory.getMaxActive());
            log.info("Memory extraction completed for run={}: memoriesSaved={}", sourceRunId, saved.size());
        } catch (Exception e) {
            log.warn("Memory persistence failed for run={}: {}", sourceRunId, e.getMessage());
        }

        return AgentHookResult.proceed();
    }

    private ExtractionResult extractWithTimeout(MemoryExtractionPayload payload,
                                                  long deadlineEpochMs,
                                                  AgentContext agentContext,
                                                  MemoryRuntimeProperties runMemory)
            throws TimeoutException, ExecutionException, InterruptedException {
        Duration timeout = Duration.ofSeconds(runMemory.getExtractionTimeoutSeconds());
        Future<ExtractionResult> future = extractionExecutor.submit(
                () -> extractionService.extract(payload, deadlineEpochMs, agentContext,
                        runMemory.getExtractionModel()));
        try {
            return future.get(timeout.getSeconds(), TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException(e.getMessage());
        }
    }

    private static ExecutorService daemonExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "memory-extraction-test");
            thread.setDaemon(true);
            return thread;
        });
    }

    private boolean isBeingDeleted(String conversationId) {
        if (conversationId == null || deletionRepository == null) {
            return false;
        }
        try {
            Optional<cn.lunalhx.ai.domain.agent.model.entity.ConversationDeletion> deletionOpt =
                    deletionRepository.find(conversationId);
            return deletionOpt.isPresent() && !"FAILED".equals(deletionOpt.get().getStatus());
        } catch (Exception e) {
            log.warn("Failed to check deletion status for conversation {}: {}", conversationId, e.getMessage());
            return false;
        }
    }
}
