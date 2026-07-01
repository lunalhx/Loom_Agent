package cn.lunalhx.ai.test;

import cn.lunalhx.ai.config.MemoryProperties;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryGenerationJobRepository;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemoryGenerationJob;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryGenerationJobStatus;
import cn.lunalhx.ai.domain.memory.service.MemoryExtractionService;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentMemoryGenerationJobRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentMemoryRepository;
import cn.lunalhx.ai.runtime.worker.MemoryExtractionWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

public class MemoryExtractionWorkerTest {

    private AgentMemoryGenerationJobRepository jobRepo;
    private AgentMemoryRepository memoryRepo;
    private MemoryProperties properties;
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        jobRepo = new InMemoryAgentMemoryGenerationJobRepository();
        memoryRepo = new InMemoryAgentMemoryRepository();
        objectMapper = new ObjectMapper();

        properties = new MemoryProperties();
        properties.setEnabled(true);
        properties.setGenerateMemories(true);
        properties.setGenerationDelayMinutes(0);
        properties.setMaxActive(200);

        MemoryProperties.WorkerConfig workerConfig = new MemoryProperties.WorkerConfig();
        workerConfig.setPollIntervalSeconds(30);
        workerConfig.setBatchSize(1);
        workerConfig.setLeaseDurationSeconds(300);
        workerConfig.setMaxRetries(3);
        workerConfig.setStaleRecoverySeconds(600);
        properties.setWorker(workerConfig);
    }

    @Test
    public void shouldProcessJobSuccessfully() {
        ModelGateway gateway = completeGateway("{\"memories\":[{\"type\":\"PREFERENCE\",\"title\":\"Uses tabs\",\"summary\":\"S\",\"body\":\"Prefers tabs over spaces.\",\"importance\":80}]}");

        MemoryExtractionService extractionService = new MemoryExtractionService(gateway, objectMapper, null);
        MemoryExtractionWorker worker = new MemoryExtractionWorker(jobRepo, memoryRepo, extractionService, properties, objectMapper);

        String summaryJson = "{\"question\":\"What do you prefer?\",\"finalAnswer\":\"I prefer tabs.\",\"step\":3,\"workspacePath\":\"/tmp/test\"}";
        AgentMemoryGenerationJob job = AgentMemoryGenerationJob.builder()
                .jobId(UUID.randomUUID().toString())
                .sourceRunId("run-worker-1")
                .workspaceKey("ws-key")
                .conversationSummaryJson(summaryJson)
                .status(MemoryGenerationJobStatus.PENDING)
                .notBefore(Instant.now())
                .retryCount(0)
                .build();
        jobRepo.insertOrIgnore(job);

        worker.poll();

        var updated = jobRepo.findBySourceRunId("run-worker-1");
        assertTrue(updated.isPresent());
        assertEquals("Job should succeed but was " + updated.get().getStatus() + ": " + updated.get().getErrorMsg(),
                MemoryGenerationJobStatus.SUCCEEDED, updated.get().getStatus());

        List<cn.lunalhx.ai.domain.memory.model.entity.AgentMemory> memories =
                memoryRepo.findBySourceRunId("run-worker-1");
        assertEquals(1, memories.size());
        assertEquals("Uses tabs", memories.get(0).getTitle());
        assertEquals(cn.lunalhx.ai.domain.memory.model.valobj.MemorySourceType.AUTO_EXTRACTION,
                memories.get(0).getSourceType());
    }

    @Test
    public void shouldSkipWhenNoMemoriesFound() {
        ModelGateway gateway = completeGateway("{\"memories\":[]}");

        MemoryExtractionService extractionService = new MemoryExtractionService(gateway, objectMapper, null);
        MemoryExtractionWorker worker = new MemoryExtractionWorker(jobRepo, memoryRepo, extractionService, properties, objectMapper);

        String summaryJson = "{\"question\":\"q\",\"finalAnswer\":\"a\",\"step\":1,\"workspacePath\":\"/tmp\"}";
        AgentMemoryGenerationJob job = AgentMemoryGenerationJob.builder()
                .jobId(UUID.randomUUID().toString())
                .sourceRunId("run-skip-1")
                .workspaceKey("ws-key")
                .conversationSummaryJson(summaryJson)
                .status(MemoryGenerationJobStatus.PENDING)
                .notBefore(Instant.now())
                .retryCount(0)
                .build();
        jobRepo.insertOrIgnore(job);

        worker.poll();

        var updated = jobRepo.findBySourceRunId("run-skip-1");
        assertTrue(updated.isPresent());
        assertEquals(MemoryGenerationJobStatus.SKIPPED, updated.get().getStatus());
    }

    @Test
    public void shouldRetryOnTransientFailure() {
        ModelGateway gateway = errorGateway(new RuntimeException("API unavailable"));

        MemoryExtractionService extractionService = new MemoryExtractionService(gateway, objectMapper, null);
        MemoryExtractionWorker worker = new MemoryExtractionWorker(jobRepo, memoryRepo, extractionService, properties, objectMapper);

        String summaryJson = "{\"question\":\"q\",\"finalAnswer\":\"a\",\"step\":1,\"workspacePath\":\"/tmp\"}";
        AgentMemoryGenerationJob job = AgentMemoryGenerationJob.builder()
                .jobId(UUID.randomUUID().toString())
                .sourceRunId("run-retry-1")
                .workspaceKey("ws-key")
                .conversationSummaryJson(summaryJson)
                .status(MemoryGenerationJobStatus.PENDING)
                .notBefore(Instant.now())
                .retryCount(0)
                .build();
        jobRepo.insertOrIgnore(job);

        worker.poll();

        var updated = jobRepo.findBySourceRunId("run-retry-1");
        assertTrue(updated.isPresent());
        assertEquals(MemoryGenerationJobStatus.PENDING, updated.get().getStatus());
        assertEquals(1, updated.get().getRetryCount());
        assertNotNull(updated.get().getErrorMsg());
        assertTrue(updated.get().getNotBefore().isAfter(Instant.now()));
    }

    @Test
    public void shouldFailAfterMaxRetries() {
        ModelGateway gateway = errorGateway(new RuntimeException("persistent error"));

        MemoryExtractionService extractionService = new MemoryExtractionService(gateway, objectMapper, null);
        MemoryExtractionWorker worker = new MemoryExtractionWorker(jobRepo, memoryRepo, extractionService, properties, objectMapper);

        String summaryJson = "{\"question\":\"q\",\"finalAnswer\":\"a\",\"step\":1,\"workspacePath\":\"/tmp\"}";
        String jobId = UUID.randomUUID().toString();
        AgentMemoryGenerationJob job = AgentMemoryGenerationJob.builder()
                .jobId(jobId)
                .sourceRunId("run-fail-max")
                .workspaceKey("ws-key")
                .conversationSummaryJson(summaryJson)
                .status(MemoryGenerationJobStatus.PENDING)
                .notBefore(Instant.now())
                .retryCount(3)
                .build();
        jobRepo.insertOrIgnore(job);

        worker.poll();

        var updated = jobRepo.findBySourceRunId("run-fail-max");
        assertTrue(updated.isPresent());
        assertEquals(MemoryGenerationJobStatus.FAILED, updated.get().getStatus());
    }

    @Test
    public void shouldDeduplicateByContentHash() {
        ModelGateway gateway = completeGateway("{\"memories\":[{\"type\":\"PREFERENCE\",\"title\":\"Same\",\"summary\":\"S\",\"body\":\"Same body content.\",\"importance\":50}]}");

        MemoryExtractionService extractionService = new MemoryExtractionService(gateway, objectMapper, null);
        MemoryExtractionWorker worker = new MemoryExtractionWorker(jobRepo, memoryRepo, extractionService, properties, objectMapper);

        String summaryJson1 = "{\"question\":\"q1\",\"finalAnswer\":\"a1\",\"step\":1,\"workspacePath\":\"/tmp\"}";
        AgentMemoryGenerationJob job1 = AgentMemoryGenerationJob.builder()
                .jobId(UUID.randomUUID().toString())
                .sourceRunId("run-dup-1")
                .workspaceKey("ws-key")
                .conversationSummaryJson(summaryJson1)
                .status(MemoryGenerationJobStatus.PENDING)
                .notBefore(Instant.now())
                .retryCount(0)
                .build();
        jobRepo.insertOrIgnore(job1);

        worker.poll();

        var result1 = jobRepo.findBySourceRunId("run-dup-1");
        assertTrue(result1.isPresent());
        assertEquals(MemoryGenerationJobStatus.SUCCEEDED, result1.get().getStatus());

        String summaryJson2 = "{\"question\":\"q2\",\"finalAnswer\":\"a2\",\"step\":1,\"workspacePath\":\"/tmp\"}";
        AgentMemoryGenerationJob job2 = AgentMemoryGenerationJob.builder()
                .jobId(UUID.randomUUID().toString())
                .sourceRunId("run-dup-2")
                .workspaceKey("ws-key")
                .conversationSummaryJson(summaryJson2)
                .status(MemoryGenerationJobStatus.PENDING)
                .notBefore(Instant.now())
                .retryCount(0)
                .build();
        jobRepo.insertOrIgnore(job2);

        worker.poll();

        var result2 = jobRepo.findBySourceRunId("run-dup-2");
        assertTrue(result2.isPresent());
        assertEquals("Duplicate content should be skipped but was " + result2.get().getStatus(),
                MemoryGenerationJobStatus.SKIPPED, result2.get().getStatus());
    }

    @Test
    public void shouldNotProcessWhenDisabled() {
        properties.setGenerateMemories(false);
        ModelGateway gateway = completeGateway("{\"memories\":[{\"type\":\"PREFERENCE\",\"title\":\"T\",\"summary\":\"S\",\"body\":\"B\",\"importance\":50}]}");

        MemoryExtractionService extractionService = new MemoryExtractionService(gateway, objectMapper, null);
        MemoryExtractionWorker worker = new MemoryExtractionWorker(jobRepo, memoryRepo, extractionService, properties, objectMapper);

        String summaryJson = "{\"question\":\"q\",\"finalAnswer\":\"a\",\"step\":1,\"workspacePath\":\"/tmp\"}";
        AgentMemoryGenerationJob job = AgentMemoryGenerationJob.builder()
                .jobId(UUID.randomUUID().toString())
                .sourceRunId("run-disabled")
                .workspaceKey("ws-key")
                .conversationSummaryJson(summaryJson)
                .status(MemoryGenerationJobStatus.PENDING)
                .notBefore(Instant.now())
                .retryCount(0)
                .build();
        jobRepo.insertOrIgnore(job);

        worker.poll();

        var updated = jobRepo.findBySourceRunId("run-disabled");
        assertTrue(updated.isPresent());
        assertEquals("Job should remain PENDING when disabled",
                MemoryGenerationJobStatus.PENDING, updated.get().getStatus());
    }

    @Test
    public void shouldHandleLegacyPayloadWithoutWorkspacePath() {
        ModelGateway gateway = completeGateway("{\"memories\":[{\"type\":\"PREFERENCE\",\"title\":\"T\",\"summary\":\"S\",\"body\":\"B\",\"importance\":50}]}");

        MemoryExtractionService extractionService = new MemoryExtractionService(gateway, objectMapper, null);
        MemoryExtractionWorker worker = new MemoryExtractionWorker(jobRepo, memoryRepo, extractionService, properties, objectMapper);

        String legacyJson = "{\"question\":\"q\",\"finalAnswer\":\"a\",\"step\":1}";
        AgentMemoryGenerationJob job = AgentMemoryGenerationJob.builder()
                .jobId(UUID.randomUUID().toString())
                .sourceRunId("run-legacy")
                .workspaceKey("ws-key")
                .conversationSummaryJson(legacyJson)
                .status(MemoryGenerationJobStatus.PENDING)
                .notBefore(Instant.now())
                .retryCount(0)
                .build();
        jobRepo.insertOrIgnore(job);

        worker.poll();

        var updated = jobRepo.findBySourceRunId("run-legacy");
        assertTrue(updated.isPresent());
        assertEquals(MemoryGenerationJobStatus.SUCCEEDED, updated.get().getStatus());
    }

    @Test
    public void shouldNotDuplicateMemoriesForSameSourceRunId() {
        ModelGateway gateway = completeGateway("{\"memories\":[{\"type\":\"PREFERENCE\",\"title\":\"T\",\"summary\":\"S\",\"body\":\"B\",\"importance\":50}]}");

        MemoryExtractionService extractionService = new MemoryExtractionService(gateway, objectMapper, null);
        MemoryExtractionWorker worker = new MemoryExtractionWorker(jobRepo, memoryRepo, extractionService, properties, objectMapper);

        String summaryJson = "{\"question\":\"q\",\"finalAnswer\":\"a\",\"step\":1,\"workspacePath\":\"/tmp\"}";
        AgentMemoryGenerationJob job = AgentMemoryGenerationJob.builder()
                .jobId(UUID.randomUUID().toString())
                .sourceRunId("run-idem")
                .workspaceKey("ws-key")
                .conversationSummaryJson(summaryJson)
                .status(MemoryGenerationJobStatus.PENDING)
                .notBefore(Instant.now())
                .retryCount(0)
                .build();
        jobRepo.insertOrIgnore(job);

        worker.poll();

        List<cn.lunalhx.ai.domain.memory.model.entity.AgentMemory> first = memoryRepo.findBySourceRunId("run-idem");
        assertEquals(1, first.size());

        AgentMemoryGenerationJob job2 = AgentMemoryGenerationJob.builder()
                .jobId(UUID.randomUUID().toString())
                .sourceRunId("run-idem-2")
                .workspaceKey("ws-key")
                .conversationSummaryJson(summaryJson)
                .status(MemoryGenerationJobStatus.PENDING)
                .notBefore(Instant.now())
                .retryCount(0)
                .build();
        jobRepo.insertOrIgnore(job2);

        worker.poll();

        List<cn.lunalhx.ai.domain.memory.model.entity.AgentMemory> all = memoryRepo.findBySourceRunId("run-idem");
        assertEquals("Should not duplicate memories", 1, all.size());
    }

    @Test
    public void shouldRecoverStaleJobsOnPoll() {
        properties.getWorker().setStaleRecoverySeconds(-1);
        properties.getWorker().setMaxRetries(3);

        ModelGateway gateway = completeGateway("{\"memories\":[]}");

        MemoryExtractionService extractionService = new MemoryExtractionService(gateway, objectMapper, null);
        MemoryExtractionWorker worker = new MemoryExtractionWorker(jobRepo, memoryRepo, extractionService, properties, objectMapper);

        String summaryJson = "{\"question\":\"q\",\"finalAnswer\":\"a\",\"step\":1,\"workspacePath\":\"/tmp\"}";
        AgentMemoryGenerationJob job = AgentMemoryGenerationJob.builder()
                .jobId(UUID.randomUUID().toString())
                .sourceRunId("run-stale-worker")
                .workspaceKey("ws-key")
                .conversationSummaryJson(summaryJson)
                .status(MemoryGenerationJobStatus.RUNNING)
                .notBefore(Instant.now().minus(Duration.ofHours(1)))
                .lockedBy("dead-worker")
                .lockExpiresAt(Instant.now().minus(Duration.ofHours(1)))
                .retryCount(1)
                .build();
        jobRepo.insertOrIgnore(job);

        worker.poll();

        var updated = jobRepo.findBySourceRunId("run-stale-worker");
        assertTrue(updated.isPresent());
        assertNotEquals(MemoryGenerationJobStatus.RUNNING, updated.get().getStatus());
    }

    private static ModelGateway completeGateway(String content) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }
            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.just(ModelChatResult.builder()
                        .content(content)
                        .finishReason("stop")
                        .build());
            }
        };
    }

    private static ModelGateway errorGateway(RuntimeException ex) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }
            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.error(ex);
            }
        };
    }
}
