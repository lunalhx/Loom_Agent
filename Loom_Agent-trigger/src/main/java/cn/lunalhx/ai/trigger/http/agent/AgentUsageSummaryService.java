package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.api.dto.AgentUsageSummaryDTO;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentErrorCode;
import cn.lunalhx.ai.domain.common.CommonErrorCode;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import cn.lunalhx.ai.types.error.ApplicationException;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentUsageSummaryService {

    private static final String MODEL_USAGE = "model_usage";
    private static final String MEMORY_EXTRACTION = "memory_extraction";

    private final AgentRunRepository agentRunRepository;
    private final TraceRecorder traceRecorder;

    public AgentUsageSummaryDTO summarize(String runId) {
        if (StringUtils.isBlank(runId)) {
            throw new ApplicationException(CommonErrorCode.INVALID_PARAMETER, "runId 不能为空");
        }
        AgentRun run = agentRunRepository.find(runId).orElse(null);
        if (run == null) {
            throw new ApplicationException(AgentErrorCode.RUN_NOT_FOUND);
        }
        String status = run.getStatus() == null ? null : run.getStatus().name();

        List<AgentTraceEvent> runEvents = traceRecorder.timeline(runId);
        String traceId = runEvents.stream()
                .map(AgentTraceEvent::getTraceId)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(null);
        if (traceId == null) {
            return AgentUsageSummaryDTO.builder()
                    .runId(runId)
                    .status(status)
                    .inputTokens(0L)
                    .outputTokens(0L)
                    .totalTokens(0L)
                    .cacheHitTokens(0L)
                    .cacheMissTokens(0L)
                    .build();
        }

        return aggregate(runId, traceId, runEvents, status);
    }

    AgentUsageSummaryDTO aggregate(String runId, String traceId, List<AgentTraceEvent> events) {
        return aggregate(runId, traceId, events, null);
    }

    private AgentUsageSummaryDTO aggregate(
            String runId,
            String traceId,
            List<AgentTraceEvent> events,
            String status) {
        long inputTokens = 0;
        long outputTokens = 0;
        long cacheHitTokens = 0;
        long cacheMissTokens = 0;
        boolean cacheUsageDeclared = false;

        for (AgentTraceEvent event : events) {
            if (!MODEL_USAGE.equals(event.getEventType())
                    || MEMORY_EXTRACTION.equals(event.getNode())
                    || event.getTokenUsage() == null) {
                continue;
            }
            TokenUsage usage = event.getTokenUsage();
            inputTokens += nonNegative(usage.getPromptTokens());
            outputTokens += nonNegative(usage.getCompletionTokens());
            if (usage.getPromptCacheHitTokens() != null
                    || usage.getPromptCacheMissTokens() != null) {
                cacheUsageDeclared = true;
                cacheHitTokens += nonNegative(usage.getPromptCacheHitTokens());
                cacheMissTokens += nonNegative(usage.getPromptCacheMissTokens());
            }
        }

        long measuredCacheTokens = cacheHitTokens + cacheMissTokens;
        BigDecimal cacheHitRate = cacheUsageDeclared && measuredCacheTokens > 0
                ? BigDecimal.valueOf(cacheHitTokens)
                        .divide(BigDecimal.valueOf(measuredCacheTokens), 4, RoundingMode.HALF_UP)
                : null;

        return AgentUsageSummaryDTO.builder()
                .runId(runId)
                .status(status)
                .traceId(traceId)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .cacheHitTokens(cacheHitTokens)
                .cacheMissTokens(cacheMissTokens)
                .cacheHitRate(cacheHitRate)
                .build();
    }

    private long nonNegative(Integer value) {
        return value == null ? 0L : Math.max(0L, value.longValue());
    }
}
