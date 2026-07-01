package cn.lunalhx.ai.test;

import cn.lunalhx.ai.api.dto.AgentReplayStreamRequest;
import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.entity.AgentReplayTimeline;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.replay.ReplayService;
import cn.lunalhx.ai.domain.agent.service.undo.WorkspaceUndoService;
import cn.lunalhx.ai.trigger.http.AgentRunController;
import cn.lunalhx.ai.trigger.http.agent.AgentRequestMapper;
import cn.lunalhx.ai.trigger.http.agent.AgentResponseMapper;
import cn.lunalhx.ai.trigger.http.agent.AgentRunHttpQueryService;
import cn.lunalhx.ai.trigger.http.agent.AgentSseResponder;
import cn.lunalhx.ai.trigger.http.agent.AgentUndoHttpService;
import cn.lunalhx.ai.types.enums.ResponseCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class AgentRunControllerContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static AgentRuntimeProperties enabledProperties() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setEnabled(true);
        properties.setTotalTimeoutMs(3000L);
        return properties;
    }

    private static ThreadPoolExecutor syncExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    }

    private MockMvc buildMockMvc(AgentRunRepository runRepo, TraceRecorder traceRecorder,
                                 ReplayService replayService, WorkspaceUndoService undoService) {
        AgentRuntimeProperties properties = enabledProperties();
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        AgentResponseMapper responseMapper = new AgentResponseMapper();
        AgentRequestMapper requestMapper = new AgentRequestMapper(properties, validator);
        AgentSseResponder sseResponder = new AgentSseResponder(properties, syncExecutor(), responseMapper);
        AgentRunHttpQueryService runHttpQueryService =
                new AgentRunHttpQueryService(runRepo, traceRecorder, replayService, responseMapper);
        AgentUndoHttpService undoHttpService = new AgentUndoHttpService(undoService);
        AgentRunController controller = new AgentRunController(
                requestMapper, sseResponder, runHttpQueryService, undoHttpService);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    // ===== 1. GET /runs/{id}/trace =====

    @Test
    public void traceSuccessShouldReturnTimeline() throws Exception {
        AgentRunRepository runRepo = mock(AgentRunRepository.class);
        when(runRepo.find("r-1")).thenReturn(Optional.of(AgentRun.builder().runId("r-1").build()));
        TraceRecorder traceRecorder = mock(TraceRecorder.class);
        AgentTraceEvent event = AgentTraceEvent.builder().id(1L).traceId("t-1").rootRunId("r-1")
                .runId("r-1").sequenceNo(1L).eventType("node_start").node("start").build();
        when(traceRecorder.timeline("r-1")).thenReturn(List.of(event));
        MockMvc mvc = buildMockMvc(runRepo, traceRecorder, mock(ReplayService.class), mock(WorkspaceUndoService.class));
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/agent/code/runs/r-1/trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.runId").value("r-1"))
                .andExpect(jsonPath("$.data.traceId").value("t-1"))
                .andExpect(jsonPath("$.data.events[0].eventType").value("node_start"));
    }

    @Test
    public void traceRunMissingShouldReturnIllegalParameter() throws Exception {
        AgentRunRepository runRepo = mock(AgentRunRepository.class);
        when(runRepo.find("missing")).thenReturn(Optional.empty());
        MockMvc mvc = buildMockMvc(runRepo, mock(TraceRecorder.class), mock(ReplayService.class), mock(WorkspaceUndoService.class));
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/agent/code/runs/missing/trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResponseCode.ILLEGAL_PARAMETER.getCode()));
    }

    @Test
    public void traceEmptyShouldReturnIllegalParameter() throws Exception {
        AgentRunRepository runRepo = mock(AgentRunRepository.class);
        when(runRepo.find("r-empty")).thenReturn(Optional.of(AgentRun.builder().runId("r-empty").build()));
        TraceRecorder traceRecorder = mock(TraceRecorder.class);
        when(traceRecorder.timeline("r-empty")).thenReturn(List.of());
        MockMvc mvc = buildMockMvc(runRepo, traceRecorder, mock(ReplayService.class), mock(WorkspaceUndoService.class));
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/agent/code/runs/r-empty/trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResponseCode.ILLEGAL_PARAMETER.getCode()));
    }

    // ===== 2. GET /runs/{id}/replay =====

    @Test
    public void replayDefaultShouldIncludeChildren() throws Exception {
        ReplayService replayService = mock(ReplayService.class);
        AgentTraceEvent event = AgentTraceEvent.builder().id(1L).sequenceNo(1L).eventType("node_start").build();
        when(replayService.replayRun(eq("r-1"), eq(true))).thenReturn(AgentReplayTimeline.builder()
                .mode("DRY_REPLAY").traceId("t-1").rootRunId("r-1").runId("r-1")
                .includeChildren(true).events(List.of(event)).costGenerated(false).build());
        MockMvc mvc = buildMockMvc(mock(AgentRunRepository.class), mock(TraceRecorder.class), replayService,
                mock(WorkspaceUndoService.class));
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/agent/code/runs/r-1/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.includeChildren").value(true))
                .andExpect(jsonPath("$.data.events[0].eventType").value("node_start"));
    }

    @Test
    public void replayEmptyShouldReturnIllegalParameter() throws Exception {
        ReplayService replayService = mock(ReplayService.class);
        when(replayService.replayRun(any(), anyBoolean())).thenReturn(AgentReplayTimeline.builder()
                .mode("DRY_REPLAY").runId("r-empty").events(List.of()).build());
        MockMvc mvc = buildMockMvc(mock(AgentRunRepository.class), mock(TraceRecorder.class), replayService,
                mock(WorkspaceUndoService.class));
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/agent/code/runs/r-empty/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResponseCode.ILLEGAL_PARAMETER.getCode()));
    }

    @Test
    public void replayQueryIncludeChildrenFalseShouldOverrideDefault() throws Exception {
        ReplayService replayService = mock(ReplayService.class);
        when(replayService.replayRun(eq("r-1"), eq(false))).thenReturn(AgentReplayTimeline.builder()
                .mode("DRY_REPLAY").runId("r-1").includeChildren(false)
                .events(List.of(AgentTraceEvent.builder().id(1L).sequenceNo(1L).build())).build());
        MockMvc mvc = buildMockMvc(mock(AgentRunRepository.class), mock(TraceRecorder.class), replayService,
                mock(WorkspaceUndoService.class));
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/agent/code/runs/r-1/replay?includeChildren=false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.includeChildren").value(false));
        verify(replayService).replayRun("r-1", false);
    }

    // ===== 3. POST /runs/{id}/replay/stream =====

    @Test
    public void replayStreamShouldEmitStartedEventsThenDoneWithZeroCost() throws Exception {
        ReplayService replayService = mock(ReplayService.class);
        AgentTraceEvent e1 = AgentTraceEvent.builder().id(1L).sequenceNo(1L).eventType("node_start").build();
        AgentTraceEvent e2 = AgentTraceEvent.builder().id(2L).sequenceNo(2L).eventType("node_end").build();
        when(replayService.replayRun(eq("r-1"), anyBoolean())).thenReturn(AgentReplayTimeline.builder()
                .mode("DRY_REPLAY").traceId("t-1").rootRunId("r-1").runId("r-1")
                .includeChildren(true).events(List.of(e1, e2)).costGenerated(false).build());
        MockMvc mvc = buildMockMvc(mock(AgentRunRepository.class), mock(TraceRecorder.class), replayService,
                mock(WorkspaceUndoService.class));
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/runs/r-1/replay/stream"))
                .andExpect(status().isOk()).andReturn();
        String content = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn().getResponse().getContentAsString();
        int started = content.indexOf("event:replay_started");
        int firstEvent = content.indexOf("event:replay_event");
        int done = content.indexOf("event:replay_done");
        assertTrue("replay_started 必须出现", started >= 0);
        assertTrue("replay_event 必须出现", firstEvent >= 0);
        assertTrue("replay_done 必须出现", done >= 0);
        assertTrue("顺序应为 started < event < done", started < firstEvent && firstEvent < done);
        assertTrue(content.contains("\"costGenerated\":false"));
    }

    @Test
    public void replayStreamQueryParamShouldOverrideRequestBody() throws Exception {
        ReplayService replayService = mock(ReplayService.class);
        when(replayService.replayRun(eq("r-1"), eq(false))).thenReturn(AgentReplayTimeline.builder()
                .mode("DRY_REPLAY").runId("r-1").includeChildren(false)
                .events(List.of(AgentTraceEvent.builder().id(1L).sequenceNo(1L).build())).build());
        MockMvc mvc = buildMockMvc(mock(AgentRunRepository.class), mock(TraceRecorder.class), replayService,
                mock(WorkspaceUndoService.class));
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/runs/r-1/replay/stream?includeChildren=false")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AgentReplayStreamRequest(true))))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn();
        verify(replayService).replayRun("r-1", false);
    }

    @Test
    public void replayStreamExceptionShouldReturnReplayFailed() throws Exception {
        ReplayService replayService = mock(ReplayService.class);
        when(replayService.replayRun(any(), anyBoolean())).thenThrow(new RuntimeException("replay boom"));
        MockMvc mvc = buildMockMvc(mock(AgentRunRepository.class), mock(TraceRecorder.class), replayService,
                mock(WorkspaceUndoService.class));
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/runs/r-1/replay/stream"))
                .andExpect(status().isOk()).andReturn();
        String content = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn().getResponse().getContentAsString();
        assertTrue(content.contains("\"code\":\"replay_failed\""));
    }
}
