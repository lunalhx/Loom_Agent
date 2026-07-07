package cn.lunalhx.ai.infrastructure.adapter.cleanup;

import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.context.ContextArtifactKind;
import cn.lunalhx.ai.domain.agent.service.context.ContextArtifactPurgeService;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ContextArtifactCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(ContextArtifactCleanupTask.class);

    private final ContextArtifactRepository artifactRepository;
    private final ContextArtifactPurgeService purgeService;
    private final AgentLoopService agentLoopService;
    private final AgentRuntimeProperties.ContextProperties config;

    public ContextArtifactCleanupTask(ContextArtifactRepository artifactRepository,
                                       ContextArtifactPurgeService purgeService,
                                       AgentLoopService agentLoopService,
                                       AgentRuntimeProperties.ContextProperties config) {
        this.artifactRepository = artifactRepository;
        this.purgeService = purgeService;
        this.agentLoopService = agentLoopService;
        this.config = config;
    }

    @Scheduled(fixedDelayString = "${loom.agent.context.transcript-cleanup-interval-ms:3600000}")
    public void cleanup() {
        if (config.getTranscriptCleanupEnabled() == null || !config.getTranscriptCleanupEnabled()) {
            return;
        }
        try {
            int retentionHours = config.getTranscriptRetentionHours() != null ? config.getTranscriptRetentionHours() : 168;
            int batchSize = config.getTranscriptCleanupBatchSize() != null ? config.getTranscriptCleanupBatchSize() : 500;
            Instant cutoff = Instant.now().minusSeconds((long) retentionHours * 3600);

            List<ContextArtifact> expired = artifactRepository.listExpiredByKind(
                    ContextArtifactKind.TRANSCRIPT, cutoff, batchSize);

            if (expired.isEmpty()) {
                return;
            }

            purgeExpiredByConversation(expired);
        } catch (Exception e) {
            log.warn("Transcript cleanup iteration failed: {}", e.getMessage());
        }
    }

    private void purgeExpiredByConversation(List<ContextArtifact> expired) {
        Map<String, List<ContextArtifact>> byConversation = expired.stream()
                .filter(a -> a.getConversationId() != null)
                .collect(Collectors.groupingBy(ContextArtifact::getConversationId));

        int totalPurged = 0;
        for (Map.Entry<String, List<ContextArtifact>> entry : byConversation.entrySet()) {
            String conversationId = entry.getKey();
            List<ContextArtifact> artifacts = entry.getValue();

            boolean hasActiveRuns;
            try {
                hasActiveRuns = agentLoopService.hasActiveRuns(conversationId);
            } catch (Exception e) {
                hasActiveRuns = false;
            }

            List<ContextArtifact> toPurge;
            if (hasActiveRuns && artifacts.size() > 1) {
                ContextArtifact latest = artifacts.stream()
                        .max(Comparator.comparing(ContextArtifact::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .orElse(null);
                toPurge = artifacts.stream()
                        .filter(a -> latest == null || !a.getArtifactId().equals(latest.getArtifactId()))
                        .toList();
            } else if (!hasActiveRuns) {
                toPurge = artifacts;
            } else {
                toPurge = List.of();
            }

            if (!toPurge.isEmpty()) {
                int purged = purgeService.purgeArtifactsNonFatal(toPurge);
                totalPurged += purged;
            }
        }

        if (totalPurged > 0) {
            log.info("Transcript cleanup purged {} expired artifacts", totalPurged);
        }
    }
}
