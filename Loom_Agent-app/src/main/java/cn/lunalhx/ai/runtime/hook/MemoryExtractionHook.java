package cn.lunalhx.ai.runtime.hook;

import cn.lunalhx.ai.config.MemoryProperties;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHook;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookContext;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookEvent;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryGenerationJobRepository;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemoryGenerationJob;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryGenerationJobStatus;
import cn.lunalhx.ai.domain.memory.service.WorkspaceKeyUtil;
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

    public MemoryExtractionHook(AgentMemoryGenerationJobRepository jobRepository,
                                 MemoryProperties memoryProperties) {
        this.jobRepository = jobRepository;
        this.memoryProperties = memoryProperties;
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

        AgentMemoryGenerationJob job = AgentMemoryGenerationJob.builder()
                .jobId(UUID.randomUUID().toString())
                .sourceRunId(sourceRunId)
                .workspaceKey(workspaceKey)
                .conversationSummaryJson(buildSummaryJson(agentContext))
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

    private String buildSummaryJson(AgentContext ctx) {
        return "{\"question\":\"" + escapeJson(ctx.getQuestion()) + "\","
                + "\"finalAnswer\":\"" + escapeJson(ctx.getFinalAnswer()) + "\","
                + "\"step\":" + ctx.getStep() + "}";
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
