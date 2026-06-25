package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentReplayTimeline;
import cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * AgentSseResponder 单元测试。
 *
 * <p>SSE 完整生命周期（event name、JSON 字段、顺序、timeout、取消等）
 * 由 {@code AgentCodeControllerContractTest} 通过 MockMvc 覆盖。
 * 本测试聚焦于同步可验证行为：emitter 创建、supplier 异常防护、StreamProfile 枚举。
 */
public class AgentSseResponderTest {

    private AgentSseResponder responder;
    private ThreadPoolExecutor executor;
    private AgentRuntimeProperties properties;

    @Before
    public void setUp() {
        properties = new AgentRuntimeProperties();
        properties.setEnabled(true);
        properties.setTotalTimeoutMs(3000L);
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        AgentResponseMapper responseMapper = new AgentResponseMapper();
        responder = new AgentSseResponder(properties, executor, responseMapper);
    }

    @After
    public void tearDown() {
        executor.shutdownNow();
    }

    // ===== completedAgentError =====

    @Test
    public void completedAgentErrorShouldReturnEmitter() {
        SseEmitter emitter = responder.completedAgentError(
                new AgentRequestMapper.Problem("invalid_request", "test error"));
        assertNotNull(emitter);
    }

    // ===== streamAgentEvents =====

    @Test
    public void streamAgentEventsAllEventsShouldReturnEmitter() {
        Flux<AgentEvent> flux = Flux.just(
                AgentEvent.builder().type(AgentEventType.DONE).runId("r").build()
        );
        SseEmitter emitter = responder.streamAgentEvents("ask", "req-1", () -> flux,
                AgentSseResponder.StreamProfile.ALL_EVENTS);
        assertNotNull(emitter);
    }

    @Test
    public void streamAgentEventsPublicAskShouldReturnEmitter() {
        Flux<AgentEvent> flux = Flux.just(
                AgentEvent.builder().type(AgentEventType.ANSWER).answer("ans").runId("r").build(),
                AgentEvent.builder().type(AgentEventType.DONE).runId("r").build()
        );
        SseEmitter emitter = responder.streamAgentEvents("ask", "req-1", () -> flux,
                AgentSseResponder.StreamProfile.PUBLIC_ASK);
        assertNotNull(emitter);
    }

    @Test
    public void streamAgentEventsWithoutCheckpointShouldReturnEmitter() {
        Flux<AgentEvent> flux = Flux.just(
                AgentEvent.builder().type(AgentEventType.RESUME_STARTED).runId("r").build(),
                AgentEvent.builder().type(AgentEventType.DONE).runId("r").build()
        );
        SseEmitter emitter = responder.streamAgentEvents("resume", "req-1", () -> flux,
                AgentSseResponder.StreamProfile.WITHOUT_CHECKPOINT);
        assertNotNull(emitter);
    }

    // ===== source supplier sync exception (必须在 responder 内部捕获) =====

    @Test
    public void sourceSupplierSyncExceptionShouldNotThrow() {
        AtomicBoolean called = new AtomicBoolean(false);
        SseEmitter emitter = responder.streamAgentEvents("ask", "req-1", () -> {
            called.set(true);
            throw new RuntimeException("supplier boom");
        }, AgentSseResponder.StreamProfile.ALL_EVENTS);
        assertNotNull(emitter);
        assertTrue("Supplier should have been called", called.get());
    }

    // ===== upstream exception =====

    @Test
    public void upstreamExceptionShouldNotThrow() {
        Flux<AgentEvent> flux = Flux.error(new RuntimeException("boom"));
        SseEmitter emitter = responder.streamAgentEvents("ask", "req-1", () -> flux,
                AgentSseResponder.StreamProfile.ALL_EVENTS);
        assertNotNull(emitter);
    }

    // ===== completedReplayError =====

    @Test
    public void completedReplayErrorShouldReturnEmitter() {
        SseEmitter emitter = responder.completedReplayError(
                new AgentRequestMapper.Problem("invalid_request", "runId 不能为空"));
        assertNotNull(emitter);
    }

    // ===== streamReplay =====

    @Test
    public void streamReplayShouldReturnEmitter() {
        AgentTraceEvent e1 = AgentTraceEvent.builder().id(1L).sequenceNo(1L).eventType("node_start").build();
        AgentReplayTimeline timeline = AgentReplayTimeline.builder()
                .mode("DRY_REPLAY").traceId("t-1").rootRunId("r-1").runId("r-1")
                .includeChildren(true).events(List.of(e1)).costGenerated(false).build();
        SseEmitter emitter = responder.streamReplay("r-1", true, () -> timeline);
        assertNotNull(emitter);
    }

    @Test
    public void streamReplayExceptionShouldNotThrow() {
        SseEmitter emitter = responder.streamReplay("r-1", true,
                () -> { throw new RuntimeException("replay boom"); });
        assertNotNull(emitter);
    }

    // ===== StreamProfile =====

    @Test
    public void streamProfileShouldHaveExactlyThreeValues() {
        AgentSseResponder.StreamProfile[] profiles = AgentSseResponder.StreamProfile.values();
        assertEquals(3, profiles.length);
    }

    @Test
    public void streamProfileValuesShouldBeDistinct() {
        AgentSseResponder.StreamProfile[] profiles = AgentSseResponder.StreamProfile.values();
        for (int i = 0; i < profiles.length; i++) {
            for (int j = i + 1; j < profiles.length; j++) {
                assertTrue("Profiles should be distinct: " + profiles[i] + " vs " + profiles[j],
                        !profiles[i].equals(profiles[j]));
            }
        }
    }
}
