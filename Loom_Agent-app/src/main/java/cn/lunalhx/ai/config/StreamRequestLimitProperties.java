package cn.lunalhx.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "loom.http.stream-limit")
public class StreamRequestLimitProperties {

    private boolean enabled = true;

    private String clientIdHeader = "";

    private boolean trustForwardedHeaders = false;

    private int maxClientStates = 10000;

    private int clientStateTtlSeconds = 3600;

    private EndpointLimit agentAsk = new EndpointLimit(8, 2, 6, 60);

    private EndpointLimit chatStream = new EndpointLimit(20, 4, 30, 60);

    @Data
    public static class EndpointLimit {
        private int maxConcurrentGlobal;
        private int maxConcurrentPerClient;
        private int maxStartsPerWindow;
        private int windowSeconds;

        public EndpointLimit() {
            this.maxConcurrentGlobal = 10;
            this.maxConcurrentPerClient = 2;
            this.maxStartsPerWindow = 10;
            this.windowSeconds = 60;
        }

        public EndpointLimit(int maxConcurrentGlobal, int maxConcurrentPerClient,
                             int maxStartsPerWindow, int windowSeconds) {
            this.maxConcurrentGlobal = maxConcurrentGlobal;
            this.maxConcurrentPerClient = maxConcurrentPerClient;
            this.maxStartsPerWindow = maxStartsPerWindow;
            this.windowSeconds = windowSeconds;
        }
    }
}
