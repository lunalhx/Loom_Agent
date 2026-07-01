package cn.lunalhx.ai.runtime.worker;

import cn.lunalhx.ai.config.MemoryProperties;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryGenerationJobRepository;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemory;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemoryGenerationJob;
import cn.lunalhx.ai.domain.memory.model.entity.MemoryExtractionPayload;
import cn.lunalhx.ai.domain.memory.model.valobj.MemorySourceType;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryStatus;
import cn.lunalhx.ai.domain.memory.service.MemoryExtractionService;
import cn.lunalhx.ai.domain.memory.service.MemoryExtractionService.ExtractedMemory;
import cn.lunalhx.ai.domain.memory.service.MemoryExtractionService.ExtractionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "loom.agent.long-term-memory.enabled", havingValue = "true")
public class MemoryExtractionWorker {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionWorker.class);

    private final AgentMemoryGenerationJobRepository jobRepository;
    private final AgentMemoryRepository memoryRepository;
    private final MemoryExtractionService extractionService;
    private final MemoryProperties memoryProperties;
    private final ObjectMapper objectMapper;
    private final String workerId;

    public MemoryExtractionWorker(AgentMemoryGenerationJobRepository jobRepository,
                                   AgentMemoryRepository memoryRepository,
                                   MemoryExtractionService extractionService,
                                   MemoryProperties memoryProperties,
                                   ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.memoryRepository = memoryRepository;
        this.extractionService = extractionService;
        this.memoryProperties = memoryProperties;
        this.objectMapper = objectMapper;
        this.workerId = UUID.randomUUID().toString();
    }

    @Scheduled(fixedDelayString = "#{@memoryProperties.worker.pollIntervalSeconds * 1000}",
               initialDelayString = "#{@memoryProperties.worker.pollIntervalSeconds * 1000}")
    public void poll() {
        if (!memoryProperties.isEnabled() || !memoryProperties.isGenerateMemories()) {
            return;
        }

        MemoryProperties.WorkerConfig workerConfig = memoryProperties.getWorker();
        Duration leaseDuration = Duration.ofSeconds(workerConfig.getLeaseDurationSeconds());
        Duration staleThreshold = Duration.ofSeconds(workerConfig.getStaleRecoverySeconds());
        int maxRetries = workerConfig.getMaxRetries();

        int recovered = jobRepository.recoverStaleJobs(staleThreshold, maxRetries);
        if (recovered > 0) {
            log.info("Recovered {} stale memory extraction jobs", recovered);
        }

        int batchSize = workerConfig.getBatchSize();
        for (int i = 0; i < batchSize; i++) {
            var jobOpt = jobRepository.claimNextPending(workerId, leaseDuration);
            if (jobOpt.isEmpty()) {
                break;
            }
            processJob(jobOpt.get(), leaseDuration, maxRetries);
        }
    }

    private void processJob(AgentMemoryGenerationJob job, Duration leaseDuration, int maxRetries) {
        String jobId = job.getJobId();
        String sourceRunId = job.getSourceRunId();
        log.info("Processing memory extraction job: jobId={}, sourceRunId={}", jobId, sourceRunId);

        try {
            MemoryExtractionPayload payload = parsePayload(job.getConversationSummaryJson());

            long deadlineEpochMs = Instant.now().plus(leaseDuration).minusSeconds(10).toEpochMilli();
            ExtractionResult result = extractionService.extract(payload, deadlineEpochMs);

            if (result.retryable()) {
                handleRetry(job, result.errorMessage(), maxRetries);
                return;
            }

            if (result.isEmpty()) {
                boolean ok = jobRepository.transitionToSkipped(jobId, workerId);
                if (ok) {
                    log.info("Memory extraction skipped (no memories found): jobId={}, sourceRunId={}", jobId, sourceRunId);
                } else {
                    log.warn("Failed to transition job to SKIPPED (lock lost?): jobId={}", jobId);
                }
                return;
            }

            List<AgentMemory> saved = saveMemories(payload, result.memories(), sourceRunId);
            if (!saved.isEmpty()) {
                boolean ok = jobRepository.transitionToSucceeded(jobId, workerId);
                if (ok) {
                    log.info("Memory extraction succeeded: jobId={}, sourceRunId={}, memoriesSaved={}",
                            jobId, sourceRunId, saved.size());
                } else {
                    log.warn("Failed to transition job to SUCCEEDED (lock lost?): jobId={}", jobId);
                }
            } else {
                boolean ok = jobRepository.transitionToSkipped(jobId, workerId);
                if (ok) {
                    log.info("Memory extraction skipped (all duplicates or at capacity): jobId={}, sourceRunId={}",
                            jobId, sourceRunId);
                } else {
                    log.warn("Failed to transition job to SKIPPED (lock lost?): jobId={}", jobId);
                }
            }
        } catch (Exception e) {
            log.warn("Memory extraction failed: jobId={}, sourceRunId={}, error={}",
                    jobId, sourceRunId, e.getClass().getSimpleName());
            handleRetry(job, e.getMessage(), maxRetries);
        }
    }

    private void handleRetry(AgentMemoryGenerationJob job, String errorMsg, int maxRetries) {
        String jobId = job.getJobId();
        int newRetryCount = job.getRetryCount() + 1;
        String truncated = truncate(errorMsg, 1000);

        if (newRetryCount > maxRetries) {
            boolean ok = jobRepository.transitionToFailed(jobId, newRetryCount, truncated);
            if (ok) {
                log.info("Memory extraction permanently failed after {} retries: jobId={}, sourceRunId={}",
                        newRetryCount, jobId, job.getSourceRunId());
            }
        } else {
            Instant notBefore = Instant.now().plus(Duration.ofSeconds(
                    memoryProperties.getWorker().getPollIntervalSeconds()));
            boolean ok = jobRepository.transitionToRetry(jobId, newRetryCount, notBefore, truncated);
            if (ok) {
                log.warn("Memory extraction will retry: jobId={}, sourceRunId={}, retry={}/{}, error={}",
                        jobId, job.getSourceRunId(), newRetryCount, maxRetries,
                        errorMsg != null ? errorMsg.getClass().getSimpleName() : "unknown");
            }
        }
    }

    private List<AgentMemory> saveMemories(MemoryExtractionPayload payload,
                                            List<ExtractedMemory> extracted,
                                            String sourceRunId) {
        String workspacePath = payload.getWorkspacePath();
        String workspaceKey = cn.lunalhx.ai.domain.memory.service.WorkspaceKeyUtil.compute(workspacePath);
        int maxActive = memoryProperties.getMaxActive();

        List<AgentMemory> existingForRun = memoryRepository.findBySourceRunId(sourceRunId);
        if (!existingForRun.isEmpty()) {
            return existingForRun;
        }

        List<AgentMemory> saved = new java.util.ArrayList<>();
        for (ExtractedMemory em : extracted) {
            List<AgentMemory> dupes = memoryRepository.findByContentHash(workspaceKey, em.contentHash());
            if (!dupes.isEmpty()) {
                continue;
            }

            int activeCount = memoryRepository.countActive(workspaceKey);
            if (activeCount >= maxActive) {
                log.info("Memory capacity reached for workspace {}: {}/{}", workspaceKey, activeCount, maxActive);
                break;
            }

            String memoryId = sha256(sourceRunId + "|" + em.contentHash());

            AgentMemory memory = AgentMemory.builder()
                    .memoryId(memoryId)
                    .workspaceKey(workspaceKey)
                    .workspacePath(workspacePath)
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

    private MemoryExtractionPayload parsePayload(String json) throws IOException {
        if (json == null || json.isBlank()) {
            return new MemoryExtractionPayload("", "", 0, "");
        }
        try {
            return objectMapper.readValue(json, MemoryExtractionPayload.class);
        } catch (IOException e) {
            log.warn("Failed to parse payload as new format, trying fallback: {}", e.getMessage());
            return parseLegacyPayload(json);
        }
    }

    private MemoryExtractionPayload parseLegacyPayload(String json) throws IOException {
        try {
            var node = objectMapper.readTree(json);
            String question = node.has("question") ? node.get("question").asText() : "";
            String finalAnswer = node.has("finalAnswer") ? node.get("finalAnswer").asText() : "";
            int step = node.has("step") ? node.get("step").asInt(0) : 0;
            return new MemoryExtractionPayload(question, finalAnswer, step, "");
        } catch (IOException e) {
            return new MemoryExtractionPayload("", "", 0, "");
        }
    }

    private static String sha256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
