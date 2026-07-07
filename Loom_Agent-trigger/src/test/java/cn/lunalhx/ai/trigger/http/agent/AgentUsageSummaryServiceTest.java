package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.api.dto.AgentUsageSummaryDTO;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AgentUsageSummaryServiceTest {

    @Test
    public void aggregateShouldIncludeTaskModelCallsAndExcludeMemoryExtraction() {
        AgentUsageSummaryService service = service();
        AgentUsageSummaryDTO summary = service.aggregate("root", "trace", List.of(
                usage("root", "model_call", 100, 10, 40, 60),
                usage("child", "model_call", 50, 5, 10, null),
                usage("root", "replan", 25, 2, null, 15),
                usage("root", "context_summary", 20, 3, 0, 20),
                usage("memory", "memory_extraction", 999, 999, 999, 999),
                AgentTraceEvent.builder().eventType("node_end").build()));

        assertEquals(Long.valueOf(195), summary.getInputTokens());
        assertEquals(Long.valueOf(20), summary.getOutputTokens());
        assertEquals(Long.valueOf(215), summary.getTotalTokens());
        assertEquals(Long.valueOf(50), summary.getCacheHitTokens());
        assertEquals(Long.valueOf(95), summary.getCacheMissTokens());
        assertEquals(new BigDecimal("0.3448"), summary.getCacheHitRate());
    }

    @Test
    public void aggregateShouldUseTokenWeightedRateAndRoundToFourDecimals() {
        AgentUsageSummaryDTO summary = service().aggregate("r", "t", List.of(
                usage("r", "model_call", 3, 1, 1, 2)));

        assertEquals(new BigDecimal("0.3333"), summary.getCacheHitRate());
    }

    @Test
    public void aggregateShouldReturnNullRateWhenProviderOmitsCacheUsage() {
        AgentUsageSummaryDTO summary = service().aggregate("r", "t", List.of(
                usage("r", "model_call", 10, 2, null, null)));

        assertEquals(Long.valueOf(10), summary.getInputTokens());
        assertEquals(Long.valueOf(2), summary.getOutputTokens());
        assertEquals(Long.valueOf(0), summary.getCacheHitTokens());
        assertEquals(Long.valueOf(0), summary.getCacheMissTokens());
        assertNull(summary.getCacheHitRate());
    }

    @Test
    public void aggregateShouldIgnoreMissingCacheFieldsPerEvent() {
        AgentUsageSummaryDTO summary = service().aggregate("r", "t", List.of(
                usage("r", "model_call", 10, 1, 10, null),
                usage("r", "model_call", 30, 2, null, 30),
                usage("r", "model_call", 5, 1, null, null)));

        assertEquals(Long.valueOf(10), summary.getCacheHitTokens());
        assertEquals(Long.valueOf(30), summary.getCacheMissTokens());
        assertEquals(new BigDecimal("0.2500"), summary.getCacheHitRate());
    }

    @Test
    public void summarizeShouldAggregateOnlyRequestedRunWhenTraceHasMultipleRuns() {
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        TraceRecorder traceRecorder = mock(TraceRecorder.class);
        when(runRepository.find("run-1")).thenReturn(Optional.of(AgentRun.builder().runId("run-1").build()));
        when(runRepository.find("run-2")).thenReturn(Optional.of(AgentRun.builder().runId("run-2").build()));
        when(traceRecorder.timeline("run-1")).thenReturn(List.of(
                usage("run-1", "model_call", 80, 20, 70, 10)));
        when(traceRecorder.timeline("run-2")).thenReturn(List.of(
                AgentTraceEvent.builder().traceId("trace-shared").runId("run-2").eventType("node_start").build(),
                usage("run-2", "model_call", 150, 50, 120, 30)));
        when(traceRecorder.timelineByTraceId("trace-shared")).thenReturn(List.of(
                usage("run-1", "model_call", 80, 20, 70, 10),
                usage("run-2", "model_call", 150, 50, 120, 30)));

        AgentUsageSummaryService service = new AgentUsageSummaryService(runRepository, traceRecorder);
        AgentUsageSummaryDTO run1 = service.summarize("run-1");
        AgentUsageSummaryDTO run2 = service.summarize("run-2");

        assertEquals(Long.valueOf(100), run1.getTotalTokens());
        assertEquals(Long.valueOf(200), run2.getTotalTokens());
        assertEquals(new BigDecimal("0.8750"), run1.getCacheHitRate());
        assertEquals(new BigDecimal("0.8000"), run2.getCacheHitRate());
    }

    private AgentUsageSummaryService service() {
        return new AgentUsageSummaryService(
                mock(AgentRunRepository.class), mock(TraceRecorder.class));
    }

    private AgentTraceEvent usage(String runId, String node, Integer input, Integer output,
                                  Integer hit, Integer miss) {
        return AgentTraceEvent.builder()
                .runId(runId)
                .traceId("trace-shared")
                .eventType("model_usage")
                .node(node)
                .tokenUsage(TokenUsage.builder()
                        .promptTokens(input)
                        .completionTokens(output)
                        .promptCacheHitTokens(hit)
                        .promptCacheMissTokens(miss)
                        .build())
                .build();
    }
}
