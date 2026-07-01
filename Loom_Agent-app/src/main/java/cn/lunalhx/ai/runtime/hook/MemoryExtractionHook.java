package cn.lunalhx.ai.runtime.hook;

import cn.lunalhx.ai.config.MemoryProperties;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHook;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookContext;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookEvent;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryGenerationJobRepository;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemoryGenerationJob;
import cn.lunalhx.ai.domain.memory.model.entity.MemoryExtractionPayload;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryGenerationJobStatus;
import cn.lunalhx.ai.domain.memory.service.WorkspaceKeyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
@Order(700)
@ConditionalOnProperty(name = "loom.agent.long-term-memory.enabled", havingValue = "true")
public class MemoryExtractionHook implements AgentHook {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionHook.class);

    private final AgentMemoryGenerationJobRepository jobRepository;
    private final MemoryProperties memoryProperties;
    private final ObjectMapper objectMapper;

    public MemoryExtractionHook(AgentMemoryGenerationJobRepository jobRepository,
                                 MemoryProperties memoryProperties,
                                 ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.memoryProperties = memoryProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentHookResult onEvent(AgentHookEvent event, AgentHookContext context) {
        if (event != AgentHookEvent.AFTER_STOP) {
            return AgentHookResult.proceed();
        }

        if (!memoryProperties.isEnabled() || !memoryProperties.isGenerateMemories()) {
            return AgentHookResult.proceed();
        }

        AgentContext agentContext = context.getAgentContext();
        if (agentContext == null) {
            return AgentHookResult.proceed();
        }

        if (agentContext.getParentRunId() != null && !agentContext.getParentRunId().isEmpty()) {
            return AgentHookResult.proceed();
        }

        if (agentContext.getFinalAnswer() == null || agentContext.getFinalAnswer().isBlank()) {
            return AgentHookResult.proceed();
        }

        String workspacePath = agentContext.getResolvedWorkspace() != null
                ? agentContext.getResolvedWorkspace().toString()
                : "";
        String workspaceKey = WorkspaceKeyUtil.compute(workspacePath);
        String sourceRunId = agentContext.getRunId();

        Instant notBefore = Instant.now().plus(Duration.ofMinutes(memoryProperties.getGenerationDelayMinutes()));

        String summaryJson;
        try {
            MemoryExtractionPayload payload = new MemoryExtractionPayload(
                    agentContext.getQuestion(),
                    agentContext.getFinalAnswer(),
                    agentContext.getStep(),
                    workspacePath);
            summaryJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize memory extraction payload for run={}: {}", sourceRunId, e.getMessage());
            return AgentHookResult.proceed();
        }

        AgentMemoryGenerationJob job = AgentMemoryGenerationJob.builder()
                .jobId(UUID.randomUUID().toString())
                .sourceRunId(sourceRunId)
                .workspaceKey(workspaceKey)
                .conversationSummaryJson(summaryJson)
                .status(MemoryGenerationJobStatus.PENDING)
                .notBefore(notBefore)
                .retryCount(0)
                .build();

        boolean created = jobRepository.insertOrIgnore(job);
        if (created) {
            log.info("Memory extraction job created: run={}, workspace={}, notBefore={}",
                    sourceRunId, workspacePath, notBefore);
        }

        return AgentHookResult.proceed();
    }
}
