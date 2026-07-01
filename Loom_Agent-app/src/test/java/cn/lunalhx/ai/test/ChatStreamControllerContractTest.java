package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.conversation.service.ChatStreamService;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.trigger.http.ChatStreamController;
import cn.lunalhx.ai.trigger.http.StreamRequestLimiter;
import cn.lunalhx.ai.trigger.http.chat.ChatSseResponder;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.After;
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

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class ChatStreamControllerContractTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private ThreadPoolExecutor executor;

    @After
    public void tearDown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    private static ModelRuntimeProperties modelProperties() {
        ModelRuntimeProperties props = new ModelRuntimeProperties();
        props.setStreamTimeoutMs(3000L);
        return props;
    }

    private ThreadPoolExecutor syncExecutor() {
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        return executor;
    }

    private ChatSseResponder chatSseResponder() {
        return new ChatSseResponder(modelProperties(), syncExecutor());
    }

    @Test
    public void streamRateLimitedShouldReturnErrorWithoutCallingService() throws Exception {
        ChatStreamService svc = mock(ChatStreamService.class);

        StreamRequestLimiter.Config config = new StreamRequestLimiter.Config();
        config.enabled = true;
        config.chatStream = new StreamRequestLimiter.EndpointLimit(0, 1, 0, 60);
        StreamRequestLimiter limiter = new StreamRequestLimiter(config);

        ChatStreamController controller = new ChatStreamController(
                svc, validator, limiter, chatSseResponder());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isOk()).andReturn();
        String content = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r))
                .andReturn().getResponse().getContentAsString();
        assertTrue(content.contains("\"code\":\"rate_limited\""));
        verify(svc, never()).stream(any());
    }

    @Test
    public void streamSuccessShouldReleaseLeaseOnCompletion() throws Exception {
        ChatStreamService svc = mock(ChatStreamService.class);
        cn.lunalhx.ai.domain.conversation.model.entity.StreamEvent doneEvent =
                cn.lunalhx.ai.domain.conversation.model.entity.StreamEvent.builder()
                        .type(cn.lunalhx.ai.domain.model.valobj.StreamEventType.DONE)
                        .build();
        when(svc.stream(any())).thenReturn(Flux.just(doneEvent));

        StreamRequestLimiter.Config config = new StreamRequestLimiter.Config();
        config.enabled = true;
        config.chatStream = new StreamRequestLimiter.EndpointLimit(1, 1, 10, 60);
        StreamRequestLimiter limiter = new StreamRequestLimiter(config);

        ChatStreamController controller = new ChatStreamController(
                svc, validator, limiter, chatSseResponder());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        // First request completes normally, releasing the lease
        MvcResult r1 = mvc.perform(MockMvcRequestBuilders.post("/api/v1/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"first\"}"))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(MockMvcRequestBuilders.asyncDispatch(r1)).andReturn();

        // Second request should succeed (lease was released)
        MvcResult r2 = mvc.perform(MockMvcRequestBuilders.post("/api/v1/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"second\"}"))
                .andExpect(status().isOk()).andReturn();
        String content2 = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r2))
                .andReturn().getResponse().getContentAsString();
        assertTrue(content2.contains("event:done"));
    }
}
