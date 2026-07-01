package cn.lunalhx.ai.trigger.http.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Slf4j
public class SseResponder {

    public Session open(long timeoutMs, Supplier<TimeoutEvent> timeoutEventSupplier, Runnable onTerminate) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        Session session = new Session(emitter, timeoutEventSupplier, onTerminate);
        session.registerCallbacks();
        return session;
    }

    public Session open(long timeoutMs, Runnable onTerminate) {
        return open(timeoutMs, null, onTerminate);
    }

    public record TimeoutEvent(String eventName, Object data) {}

    public static class Session {
        private final SseEmitter emitter;
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final AtomicBoolean terminated = new AtomicBoolean(false);
        private final AtomicBoolean onTerminateRan = new AtomicBoolean(false);
        private final AtomicReference<Disposable> subscription = new AtomicReference<>();
        private final AtomicReference<Future<?>> backgroundTask = new AtomicReference<>();
        private final Runnable onTerminate;
        private final Supplier<TimeoutEvent> timeoutEventSupplier;

        Session(SseEmitter emitter, Supplier<TimeoutEvent> timeoutEventSupplier, Runnable onTerminate) {
            this.emitter = emitter;
            this.timeoutEventSupplier = timeoutEventSupplier;
            this.onTerminate = onTerminate;
        }

        public SseEmitter emitter() {
            return emitter;
        }

        public void bind(Disposable d) {
            subscription.set(d);
            if (terminated.get() && d != null) {
                d.dispose();
            }
        }

        public void bind(Future<?> f) {
            backgroundTask.set(f);
            if (terminated.get() && f != null) {
                f.cancel(true);
            }
        }

        public void send(String eventName, Object data) {
            if (completed.get()) {
                return;
            }
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event()
                            .name(eventName)
                            .data(data, MediaType.APPLICATION_JSON));
                }
            } catch (IOException | IllegalStateException e) {
                log.warn("Failed to send SSE event: {}", e.getMessage());
                onConnectionLost();
            }
        }

        public void sendAndComplete(String eventName, Object data) {
            send(eventName, data);
            complete();
        }

        public void complete() {
            if (completed.compareAndSet(false, true)) {
                try {
                    emitter.complete();
                } catch (Exception e) {
                    log.warn("Error completing emitter: {}", e.getMessage());
                }
            }
            runOnTerminate();
        }

        private void registerCallbacks() {
            emitter.onCompletion(this::onConnectionLost);
            emitter.onTimeout(() -> {
                disposeResources();
                if (timeoutEventSupplier != null) {
                    TimeoutEvent event = timeoutEventSupplier.get();
                    send(event.eventName, event.data);
                }
                complete();
            });
            emitter.onError(e -> {
                log.warn("SSE connection closed with error: {}", e.getMessage());
                onConnectionLost();
            });
        }

        private void onConnectionLost() {
            disposeResources();
            if (completed.compareAndSet(false, true)) {
                try {
                    emitter.complete();
                } catch (Exception e) {
                    log.warn("Error completing emitter after connection loss: {}", e.getMessage());
                }
            }
            runOnTerminate();
        }

        private void disposeResources() {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            Disposable d = subscription.getAndSet(null);
            if (d != null) {
                try {
                    d.dispose();
                } catch (Exception e) {
                    log.warn("Error disposing subscription: {}", e.getMessage());
                }
            }
            Future<?> f = backgroundTask.getAndSet(null);
            if (f != null) {
                try {
                    f.cancel(true);
                } catch (Exception e) {
                    log.warn("Error cancelling background task: {}", e.getMessage());
                }
            }
        }

        private void runOnTerminate() {
            if (!onTerminateRan.compareAndSet(false, true)) {
                return;
            }
            if (onTerminate != null) {
                try {
                    onTerminate.run();
                } catch (Exception e) {
                    log.warn("onTerminate callback threw exception: {}", e.getMessage());
                }
            }
        }
    }
}
