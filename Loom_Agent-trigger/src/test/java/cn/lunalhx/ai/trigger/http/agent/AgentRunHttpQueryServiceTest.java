package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.api.dto.AgentReplayResponse;
import cn.lunalhx.ai.api.dto.AgentRunStatusResponse;
import cn.lunalhx.ai.api.dto.AgentTraceTimelineResponse;
import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.entity.AgentReplayTimeline;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.service.replay.ReplayService;
import cn.lunalhx.ai.domain.common.CommonErrorCode;
import cn.lunalhx.ai.types.error.ApplicationException;
import cn.lunalhx.ai.types.error.ErrorCategory;
import cn.lunalhx.ai.types.enums.ResponseCode;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AgentRunHttpQueryServiceTest {

    private AgentRunRepository agentRunRepository;
    private TraceRecorder traceRecorder;
    private ReplayService replayService;
    private AgentRunHttpQueryService queryService;

    @Before
    public void setUp() {
        agentRunRepository = mock(AgentRunRepository.class);
        traceRecorder = mock(TraceRecorder.class);
        replayService = mock(ReplayService.class);
        AgentResponseMapper responseMapper = new AgentResponseMapper();
        AgentUsageSummaryService usageSummaryService =
                new AgentUsageSummaryService(agentRunRepository, traceRecorder);
        queryService = new AgentRunHttpQueryService(
                agentRunRepository, traceRecorder, replayService, responseMapper, usageSummaryService);
    }

    // ===== trace =====

    @Test
    public void traceSuccessShouldReturnTimeline() {
        when(agentRunRepository.find("r-1")).thenReturn(Optional.of(AgentRun.builder()
                .runId("r-1").status(AgentRunStatus.WAITING_APPROVAL).build()));
        AgentTraceEvent event = AgentTraceEvent.builder()
                .id(1L).traceId("t-1").rootRunId("r-1").runId("r-1")
                .sequenceNo(1L).eventType("node_start").node("model").build();
        when(traceRecorder.timeline("r-1")).thenReturn(List.of(event));
        Response<AgentTraceTimelineResponse> result = queryService.trace("r-1");
        assertEquals(ResponseCode.SUCCESS.getCode(), result.getCode());
        assertEquals("r-1", result.getData().getRunId());
        assertEquals("WAITING_APPROVAL", result.getData().getStatus());
        assertEquals("t-1", result.getData().getTraceId());
        assertEquals(1, result.getData().getEvents().size());
    }

    // ===== replay =====

    @Test
    public void replaySuccessShouldReturnResponse() {
        when(agentRunRepository.find("r-1")).thenReturn(Optional.of(AgentRun.builder()
                .runId("r-1").status(AgentRunStatus.FAILED).build()));
        AgentTraceEvent event = AgentTraceEvent.builder()
                .id(1L).sequenceNo(1L).eventType("node_start").runId("r-1").build();
        when(replayService.replayRun(eq("r-1"), eq(true))).thenReturn(
                AgentReplayTimeline.builder().mode("DRY_REPLAY").traceId("t-1").rootRunId("r-1")
                        .runId("r-1").includeChildren(true).events(List.of(event)).costGenerated(false).build());
        Response<AgentReplayResponse> result = queryService.replay("r-1", true);
        assertEquals(ResponseCode.SUCCESS.getCode(), result.getCode());
        assertEquals("r-1", result.getData().getRunId());
        assertEquals("FAILED", result.getData().getStatus());
        assertEquals("node_start", result.getData().getEvents().get(0).getEventType());
    }

    @Test
    public void replayShouldPassIncludeChildrenToReplayService() {
        when(agentRunRepository.find("r-1")).thenReturn(Optional.of(AgentRun.builder()
                .runId("r-1").status(AgentRunStatus.RUNNING).build()));
        AgentTraceEvent event = AgentTraceEvent.builder()
                .id(1L).sequenceNo(1L).eventType("node_start").build();
        when(replayService.replayRun(eq("r-1"), eq(false))).thenReturn(
                AgentReplayTimeline.builder().mode("DRY_REPLAY").runId("r-1").includeChildren(false)
                        .events(List.of(event)).build());
        queryService.replay("r-1", false);
        verify(replayService).replayRun("r-1", false);
    }

    @Test
    public void waitingApprovalStatusShouldBeNonTerminalAndResumable() {
        Instant updatedAt = Instant.parse("2026-07-05T00:00:00Z");
        when(agentRunRepository.find("r-wait")).thenReturn(Optional.of(
                AgentRun.builder()
                        .runId("r-wait")
                        .status(AgentRunStatus.WAITING_APPROVAL)
                        .currentNode("approval_gate")
                        .checkpointVersion(7L)
                        .updatedAt(updatedAt)
                        .build()));

        Response<AgentRunStatusResponse> result = queryService.status("r-wait");

        assertEquals("WAITING_APPROVAL", result.getData().getStatus());
        assertEquals("approval_gate", result.getData().getCurrentNode());
        assertEquals(Long.valueOf(7L), result.getData().getCheckpointVersion());
        assertEquals(Boolean.FALSE, result.getData().getTerminal());
        assertEquals(Boolean.TRUE, result.getData().getResumable());
        assertEquals(updatedAt, result.getData().getUpdatedAt());
    }

    @Test
    public void stoppedStatusShouldBeTerminalAndNotResumable() {
        when(agentRunRepository.find("r-stopped")).thenReturn(Optional.of(
                AgentRun.builder()
                        .runId("r-stopped")
                        .status(AgentRunStatus.STOPPED)
                        .build()));

        Response<AgentRunStatusResponse> result =
                queryService.status("r-stopped");

        assertEquals(Boolean.TRUE, result.getData().getTerminal());
        assertEquals(Boolean.FALSE, result.getData().getResumable());
    }

    @Test
    public void emptyTraceForKnownRunShouldReturnLegalSnapshot() {
        when(agentRunRepository.find("r-empty")).thenReturn(Optional.of(
                AgentRun.builder()
                        .runId("r-empty")
                        .status(AgentRunStatus.WAITING_APPROVAL)
                        .build()));
        when(traceRecorder.timeline("r-empty")).thenReturn(List.of());

        Response<AgentTraceTimelineResponse> result = queryService.trace("r-empty");

        assertEquals("r-empty", result.getData().getRunId());
        assertEquals("WAITING_APPROVAL", result.getData().getStatus());
        assertTrue(result.getData().getEvents().isEmpty());
    }

    @Test
    public void unknownRunQueriesShouldReturnNotFoundCategory() {
        when(agentRunRepository.find("missing")).thenReturn(Optional.empty());

        assertRunNotFound(() -> queryService.status("missing"));
        assertRunNotFound(() -> queryService.trace("missing"));
        assertRunNotFound(() -> queryService.replay("missing", true));
        assertRunNotFound(() -> queryService.usage("missing"));
    }

    private void assertRunNotFound(Runnable action) {
        try {
            action.run();
            fail("expected run_not_found");
        } catch (ApplicationException exception) {
            assertEquals("run_not_found", exception.code());
            assertEquals(ErrorCategory.NOT_FOUND, exception.category());
        }
    }

    // ===== replayTimeline =====

    @Test
    public void replayTimelineShouldDelegateToReplayService() {
        AgentReplayTimeline timeline = AgentReplayTimeline.builder()
                .mode("DRY_REPLAY").runId("r-1").build();
        when(replayService.replayRun("r-1", true)).thenReturn(timeline);
        AgentReplayTimeline result = queryService.replayTimeline("r-1", true);
        assertEquals("r-1", result.getRunId());
        verify(replayService).replayRun("r-1", true);
    }
}
