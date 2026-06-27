package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentReplayTimeline;
import cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSseResponder {

    private final AgentRuntimeProperties agentRuntimeProperties;
    private final ThreadPoolExecutor threadPoolExecutor;
    private final AgentResponseMapper responseMapper;

    private static final Set<AgentEventType> PUBLIC_ASK_WHITELIST = EnumSet.of(
            AgentEventType.META,
            AgentEventType.PLAN_UPDATED,
            AgentEventType.REPLAN_STARTED,
            AgentEventType.CONTEXT_COMPACTED,
            AgentEventType.RESUME_STARTED,
            AgentEventType.SUB_AGENT_STARTED,
            AgentEventType.SUB_AGENT_COMPLETED,
            AgentEventType.SUB_AGENT_FAILED,
            AgentEventType.SUB_AGENT_SUMMARY,
            AgentEventType.APPROVAL_REQUIRED,
            AgentEventType.HIGH_RISK_APPROVAL_REQUIRED,
            AgentEventType.USER_INPUT_REQUIRED,
            AgentEventType.POLICY_DENIED,
            AgentEventType.ANSWER,
            AgentEventType.STOP_HOOK_RESULT,
            AgentEventType.DONE,
            AgentEventType.ERROR
    );

    public enum StreamProfile {
        PUBLIC_ASK,
        ALL_EVENTS,
        WITHOUT_CHECKPOINT
    }

    private static class Session {
        final SseEmitter emitter;
        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicReference<Disposable> subscription = new AtomicReference<>();
        final AtomicReference<java.util.concurrent.Future<?>> backgroundTask = new AtomicReference<>();

        Session(SseEmitter emitter) {
            this.emitter = emitter;
        }
    }

    public SseEmitter completedAgentError(AgentRequestMapper.Problem problem) {
        Session session = new Session(new SseEmitter(agentRuntimeProperties.getTotalTimeoutMs() + 5000L));
        threadPoolExecutor.execute(() -> {
            AgentEvent errorEvent = AgentEvent.builder()
                    .type(AgentEventType.ERROR)
                    .requestId(UUID.randomUUID().toString())
                    .stopReason(AgentStopReason.MODEL_ERROR)
                    .code(problem.code())
                    .message(problem.message())
                    .build();
            send(session, errorEvent);
            complete(session);
        });
        return session.emitter;
    }

    public SseEmitter streamAgentEvents(String operation, String requestId,
                                         Supplier<Flux<AgentEvent>> source,
                                         StreamProfile profile) {
        return streamAgentEvents(operation, requestId, source, profile, null);
    }

    public SseEmitter streamAgentEvents(String operation, String requestId,
                                         Supplier<Flux<AgentEvent>> source,
                                         StreamProfile profile,
                                         Runnable onTerminate) {
        Session session = new Session(new SseEmitter(agentRuntimeProperties.getTotalTimeoutMs() + 5000L));

        emitterOnCompletion(session, onTerminate);
        emitterOnTimeout(session, onTerminate);
        emitterOnError(session, onTerminate);

        MDC.put("request_id", requestId);
        try {
            Flux<AgentEvent> flux;
            try {
                flux = source.get();
            } catch (Exception e) {
                log.warn("Agent {} source supplier threw exception: {}", operation, e.getMessage(), e);
                sendAndComplete(session, fallbackAgentEvent());
                if (onTerminate != null) {
                    onTerminate.run();
                }
                MDC.clear();
                return session.emitter;
            }

            Disposable disposable = flux
                    .filter(event -> shouldSend(event, profile))
                    .subscribe(
                            event -> withMdc(event, () -> send(session, event)),
                            throwable -> {
                                sendAndComplete(session, fallbackAgentEvent());
                                if (onTerminate != null) {
                                    onTerminate.run();
                                }
                            },
                            () -> {
                                complete(session);
                                if (onTerminate != null) {
                                    onTerminate.run();
                                }
                            }
                    );
            session.subscription.set(disposable);
        } finally {
            MDC.clear();
        }

        return session.emitter;
    }

    public SseEmitter completedReplayError(AgentRequestMapper.Problem problem) {
        Session session = new Session(new SseEmitter(agentRuntimeProperties.getTotalTimeoutMs() + 5000L));
        threadPoolExecutor.execute(() -> {
            sendReplayAndComplete(session, "error",
                    responseMapper.replayError(problem.code(), problem.message()));
        });
        return session.emitter;
    }

    public SseEmitter streamReplay(String runId, boolean includeChildren,
                                    Supplier<AgentReplayTimeline> timelineSupplier) {
        Session session = new Session(new SseEmitter(agentRuntimeProperties.getTotalTimeoutMs() + 5000L));

        java.util.concurrent.Future<?> future = threadPoolExecutor.submit(() -> {
            try {
                AgentReplayTimeline timeline = timelineSupplier.get();
                sendReplay(session, "replay_started",
                        responseMapper.replayStarted(runId, includeChildren, timeline));
                for (AgentTraceEvent event : timeline.getEvents()) {
                    sendReplay(session, "replay_event", responseMapper.toReplayEvent(event));
                }
                sendReplayAndComplete(session, "replay_done",
                        responseMapper.replayDone(runId, timeline.getEvents().size()));
            } catch (Exception e) {
                log.warn("Replay stream failed, runId={}, message={}", runId, e.getMessage(), e);
                sendReplayAndComplete(session, "error",
                        responseMapper.replayError("replay_failed", "Replay 失败"));
            }
        });
        session.backgroundTask.set(future);

        emitterOnCompletion(session);
        emitterOnTimeoutReplay(session);
        emitterOnError(session);

        return session.emitter;
    }

    // ---- event filtering ----

    private boolean shouldSend(AgentEvent event, StreamProfile profile) {
        return switch (profile) {
            case ALL_EVENTS -> true;
            case PUBLIC_ASK -> PUBLIC_ASK_WHITELIST.contains(event.getType());
            case WITHOUT_CHECKPOINT -> event.getType() != AgentEventType.CHECKPOINT_SAVED;
        };
    }

    // ---- SSE send helpers ----

    private void send(Session session, AgentEvent event) {
        if (session.completed.get()) {
            return;
        }
        try {
            synchronized (session.emitter) {
                session.emitter.send(SseEmitter.event()
                        .name(event.getType().eventName())
                        .data(responseMapper.toStreamEvent(event), MediaType.APPLICATION_JSON));
            }
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to send agent SSE event: {}", e.getMessage());
        }
    }

    private void sendReplay(Session session, String eventName, Object data) {
        if (session.completed.get()) {
            return;
        }
        try {
            synchronized (session.emitter) {
                session.emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data, MediaType.APPLICATION_JSON));
            }
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to send replay SSE event: {}", e.getMessage());
        }
    }

    private void sendAndComplete(Session session, AgentEvent event) {
        send(session, event);
        complete(session);
    }

    private void sendReplayAndComplete(Session session, String eventName, Object data) {
        sendReplay(session, eventName, data);
        complete(session);
    }

    private void complete(Session session) {
        if (session.completed.compareAndSet(false, true)) {
            session.emitter.complete();
        }
    }

    // ---- MDC ----

    private void withMdc(AgentEvent event, Runnable runnable) {
        putEventMdc(event);
        try {
            runnable.run();
        } finally {
            MDC.clear();
        }
    }

    private void putEventMdc(AgentEvent event) {
        if (event == null) {
            return;
        }
        MDC.put("run_id", StringUtils.defaultString(event.getRunId()));
        MDC.put("request_id", StringUtils.defaultString(event.getRequestId()));
        MDC.put("conversation_id", StringUtils.defaultString(event.getConversationId()));
        MDC.put("node", StringUtils.defaultString(event.getNode()));
    }

    // ---- emitter lifecycle callbacks ----

    private void emitterOnCompletion(Session session) {
        emitterOnCompletion(session, null);
    }

    private void emitterOnCompletion(Session session, Runnable onTerminate) {
        session.emitter.onCompletion(() -> {
            Disposable d = session.subscription.get();
            if (d != null) {
                d.dispose();
            }
            if (onTerminate != null) {
                onTerminate.run();
            }
        });
    }

    private void emitterOnTimeout(Session session, Runnable onTerminate) {
        session.emitter.onTimeout(() -> {
            Disposable d = session.subscription.get();
            if (d != null) {
                d.dispose();
            }
            sendAndComplete(session, timeoutEvent());
            if (onTerminate != null) {
                onTerminate.run();
            }
        });
    }

    private void emitterOnTimeoutReplay(Session session) {
        session.emitter.onTimeout(() -> {
            java.util.concurrent.Future<?> f = session.backgroundTask.get();
            if (f != null) {
                f.cancel(true);
            }
            complete(session);
        });
    }

    private void emitterOnError(Session session) {
        emitterOnError(session, null);
    }

    private void emitterOnError(Session session, Runnable onTerminate) {
        session.emitter.onError(throwable -> {
            Disposable d = session.subscription.get();
            if (d != null) {
                d.dispose();
            }
            log.warn("Agent SSE connection closed with error: {}", throwable.getMessage());
            if (onTerminate != null) {
                onTerminate.run();
            }
        });
    }

    // ---- error events ----

    private AgentEvent fallbackAgentEvent() {
        return AgentEvent.builder()
                .type(AgentEventType.ERROR)
                .code("agent_error")
                .message("Agent 执行失败")
                .build();
    }

    private AgentEvent timeoutEvent() {
        return AgentEvent.builder()
                .type(AgentEventType.ERROR)
                .code("agent_timeout")
                .message("Agent 执行超时")
                .build();
    }
}
