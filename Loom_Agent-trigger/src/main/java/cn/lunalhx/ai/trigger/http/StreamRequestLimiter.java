package cn.lunalhx.ai.trigger.http;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class StreamRequestLimiter {

    private final Config config;

    private final AtomicInteger agentAskGlobal = new AtomicInteger(0);
    private final AtomicInteger chatStreamGlobal = new AtomicInteger(0);
    private final ConcurrentHashMap<String, ClientState> clientStates = new ConcurrentHashMap<>();

    public StreamRequestLimiter(Config config) {
        this.config = config;
    }

    public String resolveClientKey(HttpServletRequest request) {
        String header = config.clientIdHeader;
        if (header != null && !header.isEmpty()) {
            String value = request.getHeader(header);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        if (config.trustForwardedHeaders) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isEmpty()) {
                return xff.split(",")[0].trim();
            }
            String xri = request.getHeader("X-Real-IP");
            if (xri != null && !xri.isEmpty()) {
                return xri;
            }
        }
        return request.getRemoteAddr();
    }

    public Lease tryAcquire(String clientKey, String endpoint) {
        if (!config.enabled) {
            return Lease.GRANTED;
        }

        EndpointLimit limit = endpoint.equals("chat-stream")
                ? config.chatStream
                : config.agentAsk;

        ClientState state = clientStates.computeIfAbsent(clientKey, k -> {
            if (clientStates.size() >= config.maxClientStates) {
                return null;
            }
            return new ClientState();
        });

        if (state == null) {
            return new Lease(false, false, true, null);
        }

        state.touch();

        if (!state.checkWindow(limit.maxStartsPerWindow, limit.windowSeconds)) {
            return new Lease(false, true, false, null);
        }

        AtomicInteger globalCounter = endpoint.equals("chat-stream") ? chatStreamGlobal : agentAskGlobal;
        int global = globalCounter.incrementAndGet();
        if (global > limit.maxConcurrentGlobal) {
            globalCounter.decrementAndGet();
            return new Lease(false, false, true, null);
        }

        int perClient = state.inFlight(endpoint).incrementAndGet();
        if (perClient > limit.maxConcurrentPerClient) {
            state.inFlight(endpoint).decrementAndGet();
            globalCounter.decrementAndGet();
            return new Lease(false, false, true, null);
        }

        return new Lease(true, false, false,
                () -> release(globalCounter, state, endpoint));
    }

    private void release(AtomicInteger globalCounter, ClientState state, String endpoint) {
        globalCounter.decrementAndGet();
        state.inFlight(endpoint).decrementAndGet();
    }

    void evictStale() {
        long now = System.currentTimeMillis();
        long ttlMs = config.clientStateTtlSeconds * 1000L;
        clientStates.entrySet().removeIf(entry -> {
            long lastAccess = entry.getValue().lastAccess.get();
            return (now - lastAccess) > ttlMs
                    && entry.getValue().agentAskInFlight.get() <= 0
                    && entry.getValue().chatStreamInFlight.get() <= 0;
        });
    }

    public static class Config {
        public boolean enabled = true;
        public String clientIdHeader = "";
        public boolean trustForwardedHeaders = false;
        public int maxClientStates = 10000;
        public int clientStateTtlSeconds = 3600;
        public EndpointLimit agentAsk = new EndpointLimit(8, 2, 6, 60);
        public EndpointLimit chatStream = new EndpointLimit(20, 4, 30, 60);
    }

    public static class EndpointLimit {
        public final int maxConcurrentGlobal;
        public final int maxConcurrentPerClient;
        public final int maxStartsPerWindow;
        public final int windowSeconds;

        public EndpointLimit(int maxConcurrentGlobal, int maxConcurrentPerClient,
                             int maxStartsPerWindow, int windowSeconds) {
            this.maxConcurrentGlobal = maxConcurrentGlobal;
            this.maxConcurrentPerClient = maxConcurrentPerClient;
            this.maxStartsPerWindow = maxStartsPerWindow;
            this.windowSeconds = windowSeconds;
        }
    }

    public static class Lease {
        static final Lease GRANTED = new Lease(true, false, false, null);

        private final boolean allowed;
        private final boolean rateLimited;
        private final boolean concurrencyLimited;
        private final Runnable onTerminate;
        private final AtomicBoolean released = new AtomicBoolean(false);

        Lease(boolean allowed, boolean rateLimited, boolean concurrencyLimited,
              Runnable onTerminate) {
            this.allowed = allowed;
            this.rateLimited = rateLimited;
            this.concurrencyLimited = concurrencyLimited;
            this.onTerminate = onTerminate;
        }

        public boolean isAllowed() {
            return allowed;
        }

        public boolean isRateLimited() {
            return rateLimited;
        }

        public boolean isConcurrencyLimited() {
            return concurrencyLimited;
        }

        public String rejectCode() {
            return "rate_limited";
        }

        public String rejectMessage() {
            if (rateLimited) return "请求过快，请稍后重试";
            if (concurrencyLimited) return "并发请求过多，请稍后重试";
            return "请求被限流";
        }

        public void release() {
            if (!allowed || onTerminate == null) {
                return;
            }
            if (released.compareAndSet(false, true)) {
                onTerminate.run();
            }
        }
    }

    static class ClientState {
        final AtomicInteger agentAskInFlight = new AtomicInteger(0);
        final AtomicInteger chatStreamInFlight = new AtomicInteger(0);
        final AtomicLong lastAccess = new AtomicLong(System.currentTimeMillis());
        volatile Window window = new Window(0, 0, 0);

        AtomicInteger inFlight(String endpoint) {
            return endpoint.equals("chat-stream") ? chatStreamInFlight : agentAskInFlight;
        }

        void touch() {
            lastAccess.set(System.currentTimeMillis());
        }

        boolean checkWindow(int maxStarts, int windowSeconds) {
            long now = System.currentTimeMillis();
            long windowMs = windowSeconds * 1000L;
            Window w = window;
            if (now - w.startMs > windowMs) {
                if (maxStarts <= 0) {
                    return false;
                }
                window = new Window(now, 1, now + windowMs);
                return true;
            }
            if (w.count >= maxStarts) {
                return false;
            }
            window = new Window(w.startMs, w.count + 1, w.endMs);
            return true;
        }

        record Window(long startMs, int count, long endMs) {}
    }
}
