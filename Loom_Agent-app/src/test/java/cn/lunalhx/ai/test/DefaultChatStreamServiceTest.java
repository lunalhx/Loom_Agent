package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.conversation.model.entity.StreamEvent;
import cn.lunalhx.ai.domain.conversation.service.DefaultChatStreamService;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.service.OutputFormatValidator;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelGatewayException;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.OutputFormat;
import cn.lunalhx.ai.domain.model.valobj.StreamEventType;
import cn.lunalhx.ai.domain.agent.service.InMemoryTraceRecorder;
import cn.lunalhx.ai.infrastructure.gateway.ResilientModelGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class DefaultChatStreamServiceTest {

    @Test
    public void testTokenStreamOrder() {
        DefaultChatStreamService service = newService(prompt -> Flux.just(ModelStreamChunk.builder()
                .content("你好")
                .finishReason("stop")
                .build()));

        List<StreamEvent> events = service.stream(prompt(OutputFormat.TEXT)).collectList().block(Duration.ofSeconds(2));

        Assert.assertNotNull(events);
        Assert.assertEquals(StreamEventType.META, events.get(0).getType());
        Assert.assertEquals(StreamEventType.TOKEN, events.get(1).getType());
        Assert.assertEquals("你", events.get(1).getToken());
        Assert.assertEquals(StreamEventType.TOKEN, events.get(2).getType());
        Assert.assertEquals("好", events.get(2).getToken());
        Assert.assertEquals(StreamEventType.DONE, events.get(3).getType());
    }

    @Test
    public void testInvalidJsonOutputReturnsValidationError() {
        DefaultChatStreamService service = newService(prompt -> Flux.just(ModelStreamChunk.builder()
                .content("{bad")
                .finishReason("stop")
                .build()));

        List<StreamEvent> events = service.stream(prompt(OutputFormat.JSON_OBJECT)).collectList().block(Duration.ofSeconds(2));

        Assert.assertNotNull(events);
        StreamEvent last = events.get(events.size() - 1);
        Assert.assertEquals(StreamEventType.ERROR, last.getType());
        Assert.assertEquals(ModelErrorCode.VALIDATION_ERROR.code(), last.getCode());
    }

    @Test
    public void testRetryBeforeFirstToken() {
        AtomicInteger attempts = new AtomicInteger(0);
        ModelGateway flakyGateway = prompt -> {
            if (attempts.incrementAndGet() == 1) {
                return Flux.error(new ModelGatewayException(ModelErrorCode.PROVIDER_UNAVAILABLE, "503", true, 503, null));
            }
            return Flux.just(ModelStreamChunk.builder().content("好").finishReason("stop").build());
        };
        DefaultChatStreamService service = newService(resilient(flakyGateway));

        List<StreamEvent> events = service.stream(prompt(OutputFormat.TEXT)).collectList().block(Duration.ofSeconds(2));

        Assert.assertNotNull(events);
        Assert.assertEquals(2, attempts.get());
        Assert.assertEquals(StreamEventType.DONE, events.get(events.size() - 1).getType());
    }

    @Test
    public void testNoRetryAfterTokenEmitted() {
        AtomicInteger attempts = new AtomicInteger(0);
        DefaultChatStreamService service = newService(prompt -> {
            attempts.incrementAndGet();
            return Flux.concat(
                    Flux.just(ModelStreamChunk.builder().content("你").build()),
                    Flux.error(new ModelGatewayException(ModelErrorCode.PROVIDER_UNAVAILABLE, "503", true, 503, null)));
        });

        List<StreamEvent> events = service.stream(prompt(OutputFormat.TEXT)).collectList().block(Duration.ofSeconds(2));

        Assert.assertNotNull(events);
        Assert.assertEquals(1, attempts.get());
        Assert.assertEquals(StreamEventType.ERROR, events.get(events.size() - 1).getType());
    }

    private DefaultChatStreamService newService(ModelGateway modelGateway) {
        ModelRuntimeProperties properties = new ModelRuntimeProperties();
        properties.setRetryMaxAttempts(3);
        properties.setRetryBackoffInitialMs(10L);
        properties.setRetryBackoffMaxMs(20L);
        properties.setFirstTokenTimeoutMs(500L);
        properties.setStreamTimeoutMs(500L);
        return new DefaultChatStreamService(
                modelGateway,
                properties,
                new OutputFormatValidator(new ObjectMapper()),
                "deepseek-v4-flash",
                0.7D,
                1024);
    }

    private ModelGateway resilient(ModelGateway delegate) {
        ModelRuntimeProperties properties = new ModelRuntimeProperties();
        properties.setFirstTokenTimeoutMs(500L);
        properties.getResilience().setRetryMaxAttempts(3);
        properties.getResilience().setRetryBackoffInitialMs(10L);
        properties.getResilience().setRetryBackoffMaxMs(20L);
        properties.getResilience().setCircuitSlidingWindowSize(4);
        return new ResilientModelGateway(
                delegate,
                properties,
                new InMemoryTraceRecorder(),
                new SimpleMeterRegistry(),
                new MockEnvironment().withProperty("spring.ai.deepseek.chat.model", "deepseek-v4-flash"));
    }

    private ChatPrompt prompt(OutputFormat outputFormat) {
        return ChatPrompt.builder()
                .message("hello")
                .outputFormat(outputFormat)
                .build();
    }

}
