package cn.lunalhx.ai.runtime.worker;

import cn.lunalhx.ai.config.MemoryProperties;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemory;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name = "loom.agent.long-term-memory.enabled", havingValue = "true")
public class MemoryArchiveWorker {

    private static final Logger log = LoggerFactory.getLogger(MemoryArchiveWorker.class);

    private final AgentMemoryRepository memoryRepository;
    private final MemoryProperties memoryProperties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MemoryArchiveWorker(AgentMemoryRepository memoryRepository, MemoryProperties memoryProperties) {
        this.memoryRepository = memoryRepository;
        this.memoryProperties = memoryProperties;
    }

    public void runArchiveCycle() {
        if (!memoryProperties.isEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("Archive cycle already running, skipping");
            return;
        }
        try {
            int totalArchived = 0;
            int batchSize = 100;
            while (true) {
                List<AgentMemory> expired = memoryRepository.findExpiredActiveAll(
                        memoryProperties.getArchiveAfterUnusedDays(),
                        memoryProperties.getArchiveMinImportance(),
                        batchSize);
                if (expired.isEmpty()) break;

                List<String> ids = expired.stream().map(AgentMemory::getMemoryId).toList();
                int archived = memoryRepository.batchUpdateStatus(ids, MemoryStatus.ARCHIVED);
                totalArchived += archived;
            }
            if (totalArchived > 0) {
                log.info("Memory archive cycle completed: {} memories archived", totalArchived);
            }
        } catch (Exception e) {
            log.warn("Memory archive cycle failed", e);
        } finally {
            running.set(false);
        }
    }
}
