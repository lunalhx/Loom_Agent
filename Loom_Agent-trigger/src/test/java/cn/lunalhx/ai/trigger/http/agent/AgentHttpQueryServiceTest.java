package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.api.dto.AgentApprovalResponse;
import cn.lunalhx.ai.api.dto.AgentReplayResponse;
import cn.lunalhx.ai.api.dto.AgentTraceTimelineResponse;
import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.entity.AgentReplayTimeline;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.service.ReplayService;
import cn.lunalhx.ai.types.enums.ResponseCode;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AgentHttpQueryServiceTest {

    private ApprovalStore approvalStore;
    private AgentRunRepository agentRunRepository;
    private TraceRecorder traceRecorder;
    private ReplayService replayService;
    private AgentHttpQueryService queryService;

    @Before
    public void setUp() {
        approvalStore = mock(ApprovalStore.class);
        agentRunRepository = mock(AgentRunRepository.class);
        traceRecorder = mock(TraceRecorder.class);
        replayService = mock(ReplayService.class);
        AgentResponseMapper responseMapper = new AgentResponseMapper();
        queryService = new AgentHttpQueryService(approvalStore, agentRunRepository, traceRecorder, replayService, responseMapper);
    }

    // ===== approval =====

    @Test
    public void approvalFoundShouldReturnSuccess() {
        PendingApproval approval = PendingApproval.builder()
                .approvalId("ap-1").runId("r-1").requestId("req-1").conversationId("c-1")
                .build();
        when(approvalStore.find("ap-1")).thenReturn(Optional.of(approval));
        Response<AgentApprovalResponse> result = queryService.approval("ap-1");
        assertEquals(ResponseCode.SUCCESS.getCode(), result.getCode());
        assertEquals("ap-1", result.getData().getApprovalId());
        assertEquals("PENDING", result.getData().getStatus());
    }

    @Test
    public void approvalNotFoundShouldReturnIllegalParameter() {
        when(approvalStore.find("missing")).thenReturn(Optional.empty());
        Response<AgentApprovalResponse> result = queryService.approval("missing");
        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), result.getCode());
        assertEquals("审批不存在或已过期", result.getInfo());
    }

    // ===== trace =====

    @Test
    public void traceBlankRunIdShouldReturnIllegalParameter() {
        Response<AgentTraceTimelineResponse> result = queryService.trace(null);
        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), result.getCode());
        assertEquals("runId 不能为空", result.getInfo());
    }

    @Test
    public void traceRunNotFoundShouldReturnIllegalParameter() {
        when(agentRunRepository.find("r-1")).thenReturn(Optional.empty());
        Response<AgentTraceTimelineResponse> result = queryService.trace("r-1");
        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), result.getCode());
        assertEquals("未找到 run", result.getInfo());
    }

    @Test
    public void traceEmptyShouldReturnIllegalParameter() {
        when(agentRunRepository.find("r-1")).thenReturn(Optional.of(AgentRun.builder().runId("r-1").build()));
        when(traceRecorder.timeline("r-1")).thenReturn(List.of());
        Response<AgentTraceTimelineResponse> result = queryService.trace("r-1");
        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), result.getCode());
        assertEquals("未找到 trace", result.getInfo());
    }

    @Test
    public void traceSuccessShouldReturnTimeline() {
        when(agentRunRepository.find("r-1")).thenReturn(Optional.of(AgentRun.builder().runId("r-1").build()));
        AgentTraceEvent event = AgentTraceEvent.builder()
                .id(1L).traceId("t-1").rootRunId("r-1").runId("r-1")
                .sequenceNo(1L).eventType("node_start").node("model").build();
        when(traceRecorder.timeline("r-1")).thenReturn(List.of(event));
        Response<AgentTraceTimelineResponse> result = queryService.trace("r-1");
        assertEquals(ResponseCode.SUCCESS.getCode(), result.getCode());
        assertEquals("r-1", result.getData().getRunId());
        assertEquals("t-1", result.getData().getTraceId());
        assertEquals(1, result.getData().getEvents().size());
    }

    // ===== replay =====

    @Test
    public void replayBlankRunIdShouldReturnIllegalParameter() {
        Response<AgentReplayResponse> result = queryService.replay(null, true);
        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), result.getCode());
        assertEquals("runId 不能为空", result.getInfo());
    }

    @Test
    public void replayEmptyTimelineShouldReturnIllegalParameter() {
        when(replayService.replayRun(eq("r-1"), anyBoolean())).thenReturn(
                AgentReplayTimeline.builder().mode("DRY_REPLAY").runId("r-1").events(List.of()).build());
        Response<AgentReplayResponse> result = queryService.replay("r-1", true);
        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), result.getCode());
        assertEquals("未找到可 replay 的 trace", result.getInfo());
    }

    @Test
    public void replaySuccessShouldReturnResponse() {
        AgentTraceEvent event = AgentTraceEvent.builder()
                .id(1L).sequenceNo(1L).eventType("node_start").runId("r-1").build();
        when(replayService.replayRun(eq("r-1"), eq(true))).thenReturn(
                AgentReplayTimeline.builder().mode("DRY_REPLAY").traceId("t-1").rootRunId("r-1")
                        .runId("r-1").includeChildren(true).events(List.of(event)).costGenerated(false).build());
        Response<AgentReplayResponse> result = queryService.replay("r-1", true);
        assertEquals(ResponseCode.SUCCESS.getCode(), result.getCode());
        assertEquals("r-1", result.getData().getRunId());
        assertEquals("node_start", result.getData().getEvents().get(0).getEventType());
    }

    @Test
    public void replayShouldPassIncludeChildrenToReplayService() {
        AgentTraceEvent event = AgentTraceEvent.builder()
                .id(1L).sequenceNo(1L).eventType("node_start").build();
        when(replayService.replayRun(eq("r-1"), eq(false))).thenReturn(
                AgentReplayTimeline.builder().mode("DRY_REPLAY").runId("r-1").includeChildren(false)
                        .events(List.of(event)).build());
        queryService.replay("r-1", false);
        verify(replayService).replayRun("r-1", false);
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
