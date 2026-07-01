package cn.lunalhx.ai.test;

import cn.lunalhx.ai.api.dto.AgentApprovalDecisionRequest;
import cn.lunalhx.ai.api.dto.AgentApprovalResponse;
import cn.lunalhx.ai.api.dto.AgentReplayResponse;
import cn.lunalhx.ai.api.dto.AgentReplayStreamRequest;
import cn.lunalhx.ai.api.dto.AgentTraceTimelineResponse;
import cn.lunalhx.ai.api.dto.AgentUserInputRequest;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.AgentReplayTimeline;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.UserInputAction;
import cn.lunalhx.ai.domain.agent.service.AgentLoopService;
import cn.lunalhx.ai.domain.agent.service.AgentWorkspaceResolver;
import cn.lunalhx.ai.domain.agent.service.ReplayService;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import cn.lunalhx.ai.trigger.http.AgentCodeController;
import cn.lunalhx.ai.trigger.http.StreamRequestLimiter;
import cn.lunalhx.ai.trigger.http.agent.AgentHttpQueryService;
import cn.lunalhx.ai.trigger.http.agent.AgentRequestMapper;
import cn.lunalhx.ai.trigger.http.agent.AgentResponseMapper;
import cn.lunalhx.ai.trigger.http.agent.AgentSseResponder;
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
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AgentCodeController 与 SSE 契约（Phase 1 §6）。
 *
 * <p>使用 MockMvc + Mockito + 可控同步 {@link ThreadPoolExecutor}，覆盖 8 个端点的成功与失败场景。
 * SSE 测试只断言协议内容（event 名 / JSON 字段 / 顺序）与生命周期，不依赖日志文本和线程名称。
 *
 * <p>每个端点至少有一个成功和一个失败场景。
 */
public class AgentCodeControllerContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static AgentRuntimeProperties enabledProperties() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setEnabled(true);
        properties.setTotalTimeoutMs(3000L);
        return properties;
    }

    private static ThreadPoolExecutor syncExecutor() {
        // 核心线程=1、无队列延迟，execute 立即在调用线程或单线程上执行；测试结束 shutdown
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    }

    private MockMvc buildMockMvc(AgentLoopService agentLoopService,
                                 ApprovalStore approvalStore,
                                 AgentRunRepository agentRunRepository,
                                 TraceRecorder traceRecorder,
                                 ReplayService replayService,
                                 AgentRuntimeProperties properties,
                                 ThreadPoolExecutor executor) {
        return buildMockMvc(agentLoopService, approvalStore, agentRunRepository,
                traceRecorder, replayService, properties, executor, null);
    }

    private MockMvc buildMockMvc(AgentLoopService agentLoopService,
                                 ApprovalStore approvalStore,
                                 AgentRunRepository agentRunRepository,
                                 TraceRecorder traceRecorder,
                                 ReplayService replayService,
                                 AgentRuntimeProperties properties,
                                 ThreadPoolExecutor executor,
                                 StreamRequestLimiter limiter) {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        AgentResponseMapper responseMapper = new AgentResponseMapper();
        AgentRequestMapper requestMapper = new AgentRequestMapper(properties, validator);
        AgentHttpQueryService queryService = new AgentHttpQueryService(approvalStore,
                agentRunRepository, traceRecorder, replayService, responseMapper);
        AgentSseResponder sseResponder = new AgentSseResponder(properties, executor, responseMapper);
        AgentWorkspaceResolver workspaceResolver = new AgentWorkspaceResolver(properties);
        AgentCodeController controller = new AgentCodeController(agentLoopService, requestMapper,
                queryService, sseResponder, limiter != null ? limiter : noopLimiter(), null,
                null, workspaceResolver, properties);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private StreamRequestLimiter noopLimiter() {
        StreamRequestLimiter.Config config = new StreamRequestLimiter.Config();
        config.enabled = false;
        return new StreamRequestLimiter(config);
    }

    private StreamRequestLimiter restrictiveLimiter() {
        StreamRequestLimiter.Config config = new StreamRequestLimiter.Config();
        config.enabled = true;
        config.agentAsk = new StreamRequestLimiter.EndpointLimit(1, 1, 0, 60);
        return new StreamRequestLimiter(config);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    // ===== 1. /ask/stream =====

    @Test
    public void askStreamEmptyBodyShouldReturnInvalidRequest() throws Exception {
        MockMvc mvc = buildMockMvc(mock(AgentLoopService.class), mock(ApprovalStore.class),
                mock(AgentRunRepository.class), mock(TraceRecorder.class), mock(ReplayService.class),
                enabledProperties(), syncExecutor());
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/ask/stream")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
        String content = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn().getResponse().getContentAsString();
        // 空请求（question/message 都空）-> invalid_request
        assertTrue(content.contains("event:error"));
        assertTrue(content.contains("\"code\":\"invalid_request\""));
    }

    @Test
    public void askStreamAgentDisabledShouldReturnAgentDisabled() throws Exception {
        AgentRuntimeProperties properties = enabledProperties();
        properties.setEnabled(false);
        MockMvc mvc = buildMockMvc(mock(AgentLoopService.class), mock(ApprovalStore.class),
                mock(AgentRunRepository.class), mock(TraceRecorder.class), mock(ReplayService.class),
                properties, syncExecutor());
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/ask/stream")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"hi\"}"))
                .andExpect(status().isOk()).andReturn();
        String content = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn().getResponse().getContentAsString();
        assertTrue(content.contains("event:error"));
        assertTrue(content.contains("\"code\":\"agent_disabled\""));
    }

    @Test
    public void askStreamIncludeTraceFalseShouldFilterInternalNodeEvents() throws Exception {
        AgentLoopService svc = mock(AgentLoopService.class);
        // 模拟流：NODE_START（内部）+ ANSWER + DONE（用 ASCII answer 避免 SSE 默认 ISO-8859-1 编码导致中文断言失败）
        when(svc.ask(any())).thenReturn(Flux.just(
                AgentEvent.builder().type(AgentEventType.NODE_START).node("model_call").runId("r").build(),
                AgentEvent.builder().type(AgentEventType.ANSWER).answer("final-answer").runId("r").build(),
                AgentEvent.builder().type(AgentEventType.DONE).runId("r").build()));
        MockMvc mvc = buildMockMvc(svc, mock(ApprovalStore.class), mock(AgentRunRepository.class),
                mock(TraceRecorder.class), mock(ReplayService.class), enabledProperties(), syncExecutor());
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/ask/stream")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"hi\",\"includeTrace\":false}"))
                .andExpect(status().isOk()).andReturn();
        String content = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn().getResponse().getContentAsString();
        // includeTrace=false：内部节点事件 node_start 被过滤，answer/done 保留
        assertFalse("node_start 应被过滤", content.contains("event:node_start"));
        assertTrue(content.contains("event:answer"));
        assertTrue(content.contains("event:done"));
        assertTrue(content.contains("\"answer\":\"final-answer\""));
    }

    @Test
    public void askStreamIncludeTraceTrueShouldKeepAllEvents() throws Exception {
        AgentLoopService svc = mock(AgentLoopService.class);
        when(svc.ask(any())).thenReturn(Flux.just(
                AgentEvent.builder().type(AgentEventType.NODE_START).node("model_call").runId("r").build(),
                AgentEvent.builder().type(AgentEventType.ANSWER).answer("ans").runId("r").build(),
                AgentEvent.builder().type(AgentEventType.DONE).runId("r").build()));
        MockMvc mvc = buildMockMvc(svc, mock(ApprovalStore.class), mock(AgentRunRepository.class),
                mock(TraceRecorder.class), mock(ReplayService.class), enabledProperties(), syncExecutor());
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/ask/stream")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"hi\",\"includeTrace\":true}"))
                .andExpect(status().isOk()).andReturn();
        String content = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn().getResponse().getContentAsString();
        // includeTrace=true：保留全部事件，含 node_start
        assertTrue(content.contains("event:node_start"));
        assertTrue(content.contains("event:answer"));
        assertTrue(content.contains("event:done"));
    }

    // ===== 2. /approvals/{id}/decide/stream =====

    @Test
    public void decideApproveShouldMapToEnumAndCallService() throws Exception {
        AgentLoopService svc = mock(AgentLoopService.class);
        when(svc.resume(any(), eq(ApprovalDecision.APPROVE), any())).thenReturn(Flux.just(
                AgentEvent.builder().type(AgentEventType.RESUME_STARTED).runId("r").build(),
                AgentEvent.builder().type(AgentEventType.DONE).runId("r").build()));
        MockMvc mvc = buildMockMvc(svc, mock(ApprovalStore.class), mock(AgentRunRepository.class),
                mock(TraceRecorder.class), mock(ReplayService.class), enabledProperties(), syncExecutor());
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/approvals/ap-1/decide/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AgentApprovalDecisionRequest("APPROVE", "ok"))))
                .andExpect(status().isOk()).andReturn();
        String content = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn().getResponse().getContentAsString();
        // APPROVE 映射为 ApprovalDecision.APPROVE 并调用 resume
        verify(svc).resume(eq("ap-1"), eq(ApprovalDecision.APPROVE), eq("ok"));
        assertTrue(content.contains("event:resume_started"));
        assertTrue(content.contains("event:done"));
    }

    @Test
    public void decideInvalidDecisionShouldReturnErrorWithoutCallingService() throws Exception {
        AgentLoopService svc = mock(AgentLoopService.class);
        MockMvc mvc = buildMockMvc(svc, mock(ApprovalStore.class), mock(AgentRunRepository.class),
                mock(TraceRecorder.class), mock(ReplayService.class), enabledProperties(), syncExecutor());
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/approvals/ap-1/decide/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AgentApprovalDecisionRequest("MAYBE", null))))
                .andExpect(status().isOk()).andReturn();
        String content = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn().getResponse().getContentAsString();
        // 非法 decision -> invalid_request 且不调用领域服务
        assertTrue(content.contains("\"code\":\"invalid_request\""));
        verify(svc, never()).resume(any(), any(), any());
    }

    // ===== 3. GET /approvals/{id} =====

    @Test
    public void approvalFoundShouldReturnSuccess() throws Exception {
        ApprovalStore store = mock(ApprovalStore.class);
        PendingApproval approval = PendingApproval.builder()
                .approvalId("ap-1").runId("r-1").requestId("req-1").conversationId("c-1")
                .workspaceDisplayName("ws").tool("write_file")
                .permissionLevel(ToolPermissionLevel.WRITE_CONFIRM)
                .riskReason("r").operationPreview("p")
                .expiresAt(Instant.now().plusSeconds(60)).build();
        when(store.find("ap-1")).thenReturn(Optional.of(approval));
        MockMvc mvc = buildMockMvc(mock(AgentLoopService.class), store, mock(AgentRunRepository.class),
                mock(TraceRecorder.class), mock(ReplayService.class), enabledProperties(), syncExecutor());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/agent/code/approvals/ap-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.approvalId").value("ap-1"))
                .andExpect(jsonPath("$.data.tool").value("write_file"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    public void approvalNotFoundShouldReturnIllegalParameter() throws Exception {
        ApprovalStore store = mock(ApprovalStore.class);
        when(store.find("missing")).thenReturn(Optional.empty());
        MockMvc mvc = buildMockMvc(mock(AgentLoopService.class), store, mock(AgentRunRepository.class),
                mock(TraceRecorder.class), mock(ReplayService.class), enabledProperties(), syncExecutor());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/agent/code/approvals/missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResponseCode.ILLEGAL_PARAMETER.getCode()));
    }

    // ===== 4. /runs/{id}/resume/stream =====

    @Test
    public void resumeRunShouldFilterCheckpointSavedEvents() throws Exception {
        AgentLoopService svc = mock(AgentLoopService.class);
        // 模拟流含 CHECKPOINT_SAVED（应被过滤）+ RESUME_STARTED + DONE
        when(svc.resumeRun("r-1")).thenReturn(Flux.just(
                AgentEvent.builder().type(AgentEventType.CHECKPOINT_SAVED).runId("r-1").build(),
                AgentEvent.builder().type(AgentEventType.RESUME_STARTED).runId("r-1").build(),
                AgentEvent.builder().type(AgentEventType.DONE).runId("r-1").build()));
        MockMvc mvc = buildMockMvc(svc, mock(ApprovalStore.class), mock(AgentRunRepository.class),
                mock(TraceRecorder.class), mock(ReplayService.class), enabledProperties(), syncExecutor());
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/runs/r-1/resume/stream"))
                .andExpect(status().isOk()).andReturn();
        String content = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn().getResponse().getContentAsString();
        // resumeRun 过滤 CHECKPOINT_SAVED
        assertFalse(content.contains("event:checkpoint_saved"));
        assertTrue(content.contains("event:resume_started"));
        assertTrue(content.contains("event:done"));
    }

    @Test
    public void resumeRunUpstreamExceptionShouldSendErrorOnceAndComplete() throws Exception {
        AgentLoopService svc = mock(AgentLoopService.class);
        when(svc.resumeRun("r-err")).thenReturn(Flux.error(new RuntimeException("boom")));
        MockMvc mvc = buildMockMvc(svc, mock(ApprovalStore.class), mock(AgentRunRepository.class),
                mock(TraceRecorder.class), mock(ReplayService.class), enabledProperties(), syncExecutor());
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/runs/r-err/resume/stream"))
                .andExpect(status().isOk()).andReturn();
        String content = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn().getResponse().getContentAsString();
        // 上游异常只发送一次 ERROR 并完成连接
        assertEquals(1, occurrences(content, "event:error"));
        assertTrue(content.contains("\"code\":\"agent_error\""));
        // 连接完成（asyncDispatch 已解析，无异常）
    }

    // ===== 5. /runs/{id}/input/stream =====

    @Test
    public void inputContinueShouldMapToActionAndCallService() throws Exception {
        AgentLoopService svc = mock(AgentLoopService.class);
        when(svc.resumeWithUserInput(eq("r-1"), eq(UserInputAction.CONTINUE), eq("more focus")))
                .thenReturn(Flux.just(
                        AgentEvent.builder().type(AgentEventType.RESUME_STARTED).runId("r-1").build(),
                        AgentEvent.builder().type(AgentEventType.DONE).runId("r-1").build()));
        MockMvc mvc = buildMockMvc(svc, mock(ApprovalStore.class), mock(AgentRunRepository.class),
                mock(TraceRecorder.class), mock(ReplayService.class), enabledProperties(), syncExecutor());
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/runs/r-1/input/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AgentUserInputRequest("CONTINUE", "more focus"))))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn();
        // CONTINUE 映射为 UserInputAction.CONTINUE 并调用 resumeWithUserInput
        verify(svc).resumeWithUserInput(eq("r-1"), eq(UserInputAction.CONTINUE), eq("more focus"));
    }

    @Test
    public void inputInvalidActionShouldReturnErrorWithoutCallingService() throws Exception {
        AgentLoopService svc = mock(AgentLoopService.class);
        MockMvc mvc = buildMockMvc(svc, mock(ApprovalStore.class), mock(AgentRunRepository.class),
                mock(TraceRecorder.class), mock(ReplayService.class), enabledProperties(), syncExecutor());
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/runs/r-1/input/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AgentUserInputRequest("BOGUS", null))))
                .andExpect(status().isOk()).andReturn();
        String content = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn().getResponse().getContentAsString();
        assertTrue(content.contains("\"code\":\"invalid_request\""));
        verify(svc, never()).resumeWithUserInput(any(), any(), any());
    }

    // ===== 6. GET /runs/{id}/trace =====

    @Test
    public void traceSuccessShouldReturnTimeline() throws Exception {
        AgentRunRepository runRepo = mock(AgentRunRepository.class);
        when(runRepo.find("r-1")).thenReturn(Optional.of(AgentRun.builder().runId("r-1").build()));
        TraceRecorder traceRecorder = mock(TraceRecorder.class);
        AgentTraceEvent event = AgentTraceEvent.builder().id(1L).traceId("t-1").rootRunId("r-1")
                .runId("r-1").sequenceNo(1L).eventType("node_start").node("start").build();
        when(traceRecorder.timeline("r-1")).thenReturn(List.of(event));
        MockMvc mvc = buildMockMvc(mock(AgentLoopService.class), mock(ApprovalStore.class), runRepo,
                traceRecorder, mock(ReplayService.class), enabledProperties(), syncExecutor());
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
        MockMvc mvc = buildMockMvc(mock(AgentLoopService.class), mock(ApprovalStore.class), runRepo,
                mock(TraceRecorder.class), mock(ReplayService.class), enabledProperties(), syncExecutor());
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
        MockMvc mvc = buildMockMvc(mock(AgentLoopService.class), mock(ApprovalStore.class), runRepo,
                traceRecorder, mock(ReplayService.class), enabledProperties(), syncExecutor());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/agent/code/runs/r-empty/trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResponseCode.ILLEGAL_PARAMETER.getCode()));
    }

    // ===== 7. GET /runs/{id}/replay =====

    @Test
    public void replayDefaultShouldIncludeChildren() throws Exception {
        ReplayService replayService = mock(ReplayService.class);
        AgentTraceEvent event = AgentTraceEvent.builder().id(1L).sequenceNo(1L).eventType("node_start").build();
        when(replayService.replayRun(eq("r-1"), eq(true))).thenReturn(AgentReplayTimeline.builder()
                .mode("DRY_REPLAY").traceId("t-1").rootRunId("r-1").runId("r-1")
                .includeChildren(true).events(List.of(event)).costGenerated(false).build());
        MockMvc mvc = buildMockMvc(mock(AgentLoopService.class), mock(ApprovalStore.class),
                mock(AgentRunRepository.class), mock(TraceRecorder.class), replayService,
                enabledProperties(), syncExecutor());
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
        MockMvc mvc = buildMockMvc(mock(AgentLoopService.class), mock(ApprovalStore.class),
                mock(AgentRunRepository.class), mock(TraceRecorder.class), replayService,
                enabledProperties(), syncExecutor());
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
        MockMvc mvc = buildMockMvc(mock(AgentLoopService.class), mock(ApprovalStore.class),
                mock(AgentRunRepository.class), mock(TraceRecorder.class), replayService,
                enabledProperties(), syncExecutor());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/agent/code/runs/r-1/replay?includeChildren=false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.includeChildren").value(false));
        verify(replayService).replayRun("r-1", false);
    }

    // ===== 8. POST /runs/{id}/replay/stream =====

    @Test
    public void replayStreamShouldEmitStartedEventsThenDoneWithZeroCost() throws Exception {
        ReplayService replayService = mock(ReplayService.class);
        AgentTraceEvent e1 = AgentTraceEvent.builder().id(1L).sequenceNo(1L).eventType("node_start").build();
        AgentTraceEvent e2 = AgentTraceEvent.builder().id(2L).sequenceNo(2L).eventType("node_end").build();
        when(replayService.replayRun(eq("r-1"), anyBoolean())).thenReturn(AgentReplayTimeline.builder()
                .mode("DRY_REPLAY").traceId("t-1").rootRunId("r-1").runId("r-1")
                .includeChildren(true).events(List.of(e1, e2)).costGenerated(false).build());
        MockMvc mvc = buildMockMvc(mock(AgentLoopService.class), mock(ApprovalStore.class),
                mock(AgentRunRepository.class), mock(TraceRecorder.class), replayService,
                enabledProperties(), syncExecutor());
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/runs/r-1/replay/stream"))
                .andExpect(status().isOk()).andReturn();
        String content = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn().getResponse().getContentAsString();
        // 顺序 replay_started -> replay_event* -> replay_done
        int started = content.indexOf("event:replay_started");
        int firstEvent = content.indexOf("event:replay_event");
        int done = content.indexOf("event:replay_done");
        assertTrue("replay_started 必须出现", started >= 0);
        assertTrue("replay_event 必须出现", firstEvent >= 0);
        assertTrue("replay_done 必须出现", done >= 0);
        assertTrue("顺序应为 started < event < done",
                started < firstEvent && firstEvent < done);
        // costGenerated=false
        assertTrue(content.contains("\"costGenerated\":false"));
    }

    @Test
    public void replayStreamQueryParamShouldOverrideRequestBody() throws Exception {
        ReplayService replayService = mock(ReplayService.class);
        when(replayService.replayRun(eq("r-1"), eq(false))).thenReturn(AgentReplayTimeline.builder()
                .mode("DRY_REPLAY").runId("r-1").includeChildren(false)
                .events(List.of(AgentTraceEvent.builder().id(1L).sequenceNo(1L).build())).build());
        MockMvc mvc = buildMockMvc(mock(AgentLoopService.class), mock(ApprovalStore.class),
                mock(AgentRunRepository.class), mock(TraceRecorder.class), replayService,
                enabledProperties(), syncExecutor());
        // query includeChildren=false，body includeChildren=true -> query 优先
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
        MockMvc mvc = buildMockMvc(mock(AgentLoopService.class), mock(ApprovalStore.class),
                mock(AgentRunRepository.class), mock(TraceRecorder.class), replayService,
                enabledProperties(), syncExecutor());
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/runs/r-1/replay/stream"))
                .andExpect(status().isOk()).andReturn();
        String content = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn().getResponse().getContentAsString();
        // replay 异常 -> replay_failed
        assertTrue(content.contains("\"code\":\"replay_failed\""));
    }

    // ===== rate limiting =====

    @Test
    public void askStreamRateLimitedShouldReturnErrorWithoutCallingService() throws Exception {
        AgentLoopService svc = mock(AgentLoopService.class);
        MockMvc mvc = buildMockMvc(svc, mock(ApprovalStore.class), mock(AgentRunRepository.class),
                mock(TraceRecorder.class), mock(ReplayService.class), enabledProperties(),
                syncExecutor(), restrictiveLimiter());
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/ask/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"hi\"}"))
                .andExpect(status().isOk()).andReturn();
        String content = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn().getResponse().getContentAsString();
        assertTrue(content.contains("event:error"));
        assertTrue(content.contains("\"code\":\"rate_limited\""));
        verify(svc, never()).ask(any());
    }

    @Test
    public void askStreamFirstRequestHoldsLeaseSecondSameClientRejected() throws Exception {
        AgentLoopService svc = mock(AgentLoopService.class);
        when(svc.ask(any())).thenReturn(Flux.never());

        StreamRequestLimiter.Config testConfig = new StreamRequestLimiter.Config();
        testConfig.enabled = true;
        testConfig.agentAsk = new StreamRequestLimiter.EndpointLimit(5, 1, 10, 60);
        StreamRequestLimiter limiter = new StreamRequestLimiter(testConfig);

        MockMvc mvc = buildMockMvc(svc, mock(ApprovalStore.class), mock(AgentRunRepository.class),
                mock(TraceRecorder.class), mock(ReplayService.class), enabledProperties(),
                syncExecutor(), limiter);

        // First request: starts the flux and holds the lease
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/ask/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"first\"}"));

        // Second request: same client, should be rejected
        MvcResult r2 = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/ask/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"second\"}"))
                .andExpect(status().isOk()).andReturn();
        String content2 = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r2)).andReturn().getResponse().getContentAsString();
        assertTrue(content2.contains("\"code\":\"rate_limited\""));
    }

    // ===== 辅助 =====

    private static int occurrences(String text, String token) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) >= 0) {
            count++;
            idx += token.length();
        }
        return count;
    }
}
