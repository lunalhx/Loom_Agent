package cn.lunalhx.ai.test;

import cn.lunalhx.ai.api.dto.AgentAskRequest;
import cn.lunalhx.ai.api.dto.AgentApprovalDecisionRequest;
import cn.lunalhx.ai.api.dto.AgentUserInputRequest;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.conversation.ConversationDeletionService;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.trigger.http.AgentExecutionController;
import cn.lunalhx.ai.trigger.http.StreamRequestLimiter;
import cn.lunalhx.ai.trigger.http.agent.AgentRequestMapper;
import cn.lunalhx.ai.trigger.http.agent.AgentResponseMapper;
import cn.lunalhx.ai.trigger.http.agent.AgentSseResponder;
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

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class AgentExecutionControllerContractTest {

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

    private MockMvc buildMockMvc(AgentLoopService agentLoopService,
                                 AgentRuntimeProperties properties,
                                 ThreadPoolExecutor executor,
                                 StreamRequestLimiter limiter,
                                 ConversationDeletionService deletionService) {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        AgentResponseMapper responseMapper = new AgentResponseMapper();
        AgentRequestMapper requestMapper = new AgentRequestMapper(properties, validator);
        AgentSseResponder sseResponder = new AgentSseResponder(properties, executor, responseMapper);
        AgentExecutionController controller = new AgentExecutionController(
                agentLoopService, requestMapper, sseResponder, limiter, deletionService);
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

    private ConversationDeletionService noopDeletionService() {
        ConversationDeletionService svc = mock(ConversationDeletionService.class);
        when(svc.isConversationDeleted(any())).thenReturn(false);
        return svc;
    }

    // ===== 1. /ask/stream =====

    @Test
    public void askStreamEmptyBodyShouldReturnInvalidRequest() throws Exception {
        MockMvc mvc = buildMockMvc(mock(AgentLoopService.class), enabledProperties(), syncExecutor(),
                noopLimiter(), noopDeletionService());
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/ask/stream")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
        String content = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn().getResponse().getContentAsString();
        assertTrue(content.contains("event:error"));
        assertTrue(content.contains("\"code\":\"invalid_request\""));
    }

    @Test
    public void askStreamAgentDisabledShouldReturnAgentDisabled() throws Exception {
        AgentRuntimeProperties properties = enabledProperties();
        properties.setEnabled(false);
        MockMvc mvc = buildMockMvc(mock(AgentLoopService.class), properties, syncExecutor(),
                noopLimiter(), noopDeletionService());
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
        when(svc.ask(any())).thenReturn(Flux.just(
                AgentEvent.builder().type(AgentEventType.NODE_START).node("model_call").runId("r").build(),
                AgentEvent.builder().type(AgentEventType.ANSWER).answer("final-answer").runId("r").build(),
                AgentEvent.builder().type(AgentEventType.DONE).runId("r").build()));
        MockMvc mvc = buildMockMvc(svc, enabledProperties(), syncExecutor(), noopLimiter(), noopDeletionService());
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/ask/stream")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"hi\",\"includeTrace\":false}"))
                .andExpect(status().isOk()).andReturn();
        String content = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn().getResponse().getContentAsString();
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
        MockMvc mvc = buildMockMvc(svc, enabledProperties(), syncExecutor(), noopLimiter(), noopDeletionService());
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/ask/stream")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"hi\",\"includeTrace\":true}"))
                .andExpect(status().isOk()).andReturn();
        String content = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn().getResponse().getContentAsString();
        assertTrue(content.contains("event:node_start"));
        assertTrue(content.contains("event:answer"));
        assertTrue(content.contains("event:done"));
    }

    // ===== 2. /runs/{id}/resume/stream =====

    @Test
    public void resumeRunShouldFilterCheckpointSavedEvents() throws Exception {
        AgentLoopService svc = mock(AgentLoopService.class);
        when(svc.resumeRun("r-1")).thenReturn(Flux.just(
                AgentEvent.builder().type(AgentEventType.CHECKPOINT_SAVED).runId("r-1").build(),
                AgentEvent.builder().type(AgentEventType.RESUME_STARTED).runId("r-1").build(),
                AgentEvent.builder().type(AgentEventType.DONE).runId("r-1").build()));
        MockMvc mvc = buildMockMvc(svc, enabledProperties(), syncExecutor(), noopLimiter(), noopDeletionService());
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/runs/r-1/resume/stream"))
                .andExpect(status().isOk()).andReturn();
        String content = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn().getResponse().getContentAsString();
        assertFalse(content.contains("event:checkpoint_saved"));
        assertTrue(content.contains("event:resume_started"));
        assertTrue(content.contains("event:done"));
    }

    @Test
    public void resumeRunUpstreamExceptionShouldSendErrorOnceAndComplete() throws Exception {
        AgentLoopService svc = mock(AgentLoopService.class);
        when(svc.resumeRun("r-err")).thenReturn(Flux.error(new RuntimeException("boom")));
        MockMvc mvc = buildMockMvc(svc, enabledProperties(), syncExecutor(), noopLimiter(), noopDeletionService());
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/runs/r-err/resume/stream"))
                .andExpect(status().isOk()).andReturn();
        String content = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn().getResponse().getContentAsString();
        assertEquals(1, occurrences(content, "event:error"));
        assertTrue(content.contains("\"code\":\"agent_error\""));
    }

    // ===== 3. /runs/{id}/input/stream =====

    @Test
    public void inputContinueShouldMapToActionAndCallService() throws Exception {
        AgentLoopService svc = mock(AgentLoopService.class);
        when(svc.resumeWithUserInput(eq("r-1"), eq(cn.lunalhx.ai.domain.agent.model.valobj.UserInputAction.CONTINUE), eq("more focus")))
                .thenReturn(Flux.just(
                        AgentEvent.builder().type(AgentEventType.RESUME_STARTED).runId("r-1").build(),
                        AgentEvent.builder().type(AgentEventType.DONE).runId("r-1").build()));
        MockMvc mvc = buildMockMvc(svc, enabledProperties(), syncExecutor(), noopLimiter(), noopDeletionService());
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/runs/r-1/input/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AgentUserInputRequest("CONTINUE", "more focus"))))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn();
        verify(svc).resumeWithUserInput(eq("r-1"), eq(cn.lunalhx.ai.domain.agent.model.valobj.UserInputAction.CONTINUE), eq("more focus"));
    }

    @Test
    public void inputInvalidActionShouldReturnErrorWithoutCallingService() throws Exception {
        AgentLoopService svc = mock(AgentLoopService.class);
        MockMvc mvc = buildMockMvc(svc, enabledProperties(), syncExecutor(), noopLimiter(), noopDeletionService());
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/runs/r-1/input/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AgentUserInputRequest("BOGUS", null))))
                .andExpect(status().isOk()).andReturn();
        String content = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn().getResponse().getContentAsString();
        assertTrue(content.contains("\"code\":\"invalid_request\""));
        verify(svc, never()).resumeWithUserInput(any(), any(), any());
    }

    // ===== 4. POST /runs/{id}/cancel =====

    @Test
    public void cancelRunShouldDelegateToAgentLoopService() throws Exception {
        AgentLoopService service = mock(AgentLoopService.class);
        when(service.cancelRun("r-1")).thenReturn(true);
        MockMvc mvc = buildMockMvc(service, enabledProperties(), syncExecutor(), noopLimiter(), noopDeletionService());
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/runs/r-1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0000"))
                .andExpect(jsonPath("$.data").value(true));
        verify(service).cancelRun("r-1");
    }

    @Test
    public void cancelInactiveRunShouldReturnFalse() throws Exception {
        AgentLoopService service = mock(AgentLoopService.class);
        MockMvc mvc = buildMockMvc(service, enabledProperties(), syncExecutor(), noopLimiter(), noopDeletionService());
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/runs/missing/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0000"))
                .andExpect(jsonPath("$.data").value(false));
    }

    // ===== rate limiting =====

    @Test
    public void askStreamRateLimitedShouldReturnErrorWithoutCallingService() throws Exception {
        AgentLoopService svc = mock(AgentLoopService.class);
        MockMvc mvc = buildMockMvc(svc, enabledProperties(), syncExecutor(), restrictiveLimiter(), noopDeletionService());
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

        MockMvc mvc = buildMockMvc(svc, enabledProperties(), syncExecutor(), limiter, noopDeletionService());

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/ask/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"first\"}"));

        MvcResult r2 = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/ask/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"second\"}"))
                .andExpect(status().isOk()).andReturn();
        String content2 = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r2)).andReturn().getResponse().getContentAsString();
        assertTrue(content2.contains("\"code\":\"rate_limited\""));
    }

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
