package cn.lunalhx.ai.test;

import cn.lunalhx.ai.trigger.http.StreamRequestLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class StreamRequestLimiterTest {

    private StreamRequestLimiter.Config config;
    private StreamRequestLimiter limiter;

    @Before
    public void setUp() {
        config = new StreamRequestLimiter.Config();
        config.enabled = true;
        config.maxClientStates = 100;
        config.clientStateTtlSeconds = 3600;
        config.agentAsk = new StreamRequestLimiter.EndpointLimit(2, 1, 3, 60);
        limiter = new StreamRequestLimiter(config);
    }

    @Test
    public void sameClientExceedsWindowShouldBeRejected() {
        HttpServletRequest req = mockRequest("127.0.0.1");
        String key = limiter.resolveClientKey(req);

        for (int i = 0; i < 3; i++) {
            StreamRequestLimiter.Lease lease = limiter.tryAcquire(key, "agent-ask");
            assertTrue("request " + i + " should be allowed", lease.isAllowed());
            lease.release();
        }

        StreamRequestLimiter.Lease rejected = limiter.tryAcquire(key, "agent-ask");
        assertFalse(rejected.isAllowed());
        assertTrue(rejected.isRateLimited());
        assertEquals("rate_limited", rejected.rejectCode());
    }

    @Test
    public void globalConcurrencyLimitShouldRejectNewRequests() {
        HttpServletRequest req1 = mockRequest("10.0.0.1");
        HttpServletRequest req2 = mockRequest("10.0.0.2");
        HttpServletRequest req3 = mockRequest("10.0.0.3");
        String key1 = limiter.resolveClientKey(req1);
        String key2 = limiter.resolveClientKey(req2);
        String key3 = limiter.resolveClientKey(req3);

        StreamRequestLimiter.Lease l1 = limiter.tryAcquire(key1, "agent-ask");
        StreamRequestLimiter.Lease l2 = limiter.tryAcquire(key2, "agent-ask");
        assertTrue(l1.isAllowed());
        assertTrue(l2.isAllowed());

        StreamRequestLimiter.Lease l3 = limiter.tryAcquire(key3, "agent-ask");
        assertFalse(l3.isAllowed());
        assertTrue(l3.isConcurrencyLimited());

        l1.release();
        StreamRequestLimiter.Lease l4 = limiter.tryAcquire(key3, "agent-ask");
        assertTrue(l4.isAllowed());
        l2.release();
        l4.release();
    }

    @Test
    public void perClientConcurrencyLimitShouldOnlyAffectSameClient() {
        HttpServletRequest reqA = mockRequest("10.0.0.1");
        HttpServletRequest reqB = mockRequest("10.0.0.2");
        String keyA = limiter.resolveClientKey(reqA);
        String keyB = limiter.resolveClientKey(reqB);

        StreamRequestLimiter.Lease l1 = limiter.tryAcquire(keyA, "agent-ask");
        assertTrue(l1.isAllowed());

        StreamRequestLimiter.Lease l2 = limiter.tryAcquire(keyA, "agent-ask");
        assertFalse("same client second request should be rejected", l2.isAllowed());

        StreamRequestLimiter.Lease l3 = limiter.tryAcquire(keyB, "agent-ask");
        assertTrue("different client should still be allowed", l3.isAllowed());

        l1.release();
        l3.release();
    }

    @Test
    public void releaseShouldBeIdempotent() {
        HttpServletRequest req = mockRequest("10.0.0.1");
        String key = limiter.resolveClientKey(req);

        StreamRequestLimiter.Lease lease = limiter.tryAcquire(key, "agent-ask");
        assertTrue(lease.isAllowed());
        lease.release();
        lease.release();

        StreamRequestLimiter.Lease next = limiter.tryAcquire(key, "agent-ask");
        assertTrue("should be able to acquire after release", next.isAllowed());
        next.release();
    }

    @Test
    public void disabledShouldAlwaysAllow() {
        StreamRequestLimiter.Config disabledConfig = new StreamRequestLimiter.Config();
        disabledConfig.enabled = false;
        StreamRequestLimiter disabledLimiter = new StreamRequestLimiter(disabledConfig);
        HttpServletRequest req = mockRequest("10.0.0.1");
        String key = disabledLimiter.resolveClientKey(req);

        for (int i = 0; i < 100; i++) {
            StreamRequestLimiter.Lease lease = disabledLimiter.tryAcquire(key, "agent-ask");
            assertTrue(lease.isAllowed());
        }
    }

    @Test
    public void resolveClientKeyDefaultUsesRemoteAddr() {
        HttpServletRequest req = mockRequest("192.168.1.1");
        assertEquals("192.168.1.1", limiter.resolveClientKey(req));
    }

    @Test
    public void resolveClientKeyUsesConfiguredHeader() {
        StreamRequestLimiter.Config headerConfig = new StreamRequestLimiter.Config();
        headerConfig.clientIdHeader = "X-Client-Id";
        StreamRequestLimiter headerLimiter = new StreamRequestLimiter(headerConfig);
        HttpServletRequest req = mockRequest("10.0.0.1");
        when(req.getHeader("X-Client-Id")).thenReturn("user-123");
        when(req.getRemoteAddr()).thenReturn("10.0.0.1");

        assertEquals("user-123", headerLimiter.resolveClientKey(req));
    }

    @Test
    public void resolveClientKeyFallsBackToRemoteAddrWhenHeaderMissing() {
        StreamRequestLimiter.Config headerConfig = new StreamRequestLimiter.Config();
        headerConfig.clientIdHeader = "X-Client-Id";
        StreamRequestLimiter headerLimiter = new StreamRequestLimiter(headerConfig);
        HttpServletRequest req = mockRequest("10.0.0.1");
        when(req.getHeader("X-Client-Id")).thenReturn(null);
        when(req.getRemoteAddr()).thenReturn("10.0.0.1");

        assertEquals("10.0.0.1", headerLimiter.resolveClientKey(req));
    }

    @Test
    public void resolveClientKeyUsesXForwardedForWhenTrusted() {
        StreamRequestLimiter.Config trustedConfig = new StreamRequestLimiter.Config();
        trustedConfig.trustForwardedHeaders = true;
        StreamRequestLimiter trustedLimiter = new StreamRequestLimiter(trustedConfig);
        HttpServletRequest req = mockRequest("10.0.0.1");
        when(req.getHeader("X-Forwarded-For")).thenReturn("203.0.113.1, 10.0.0.1");
        when(req.getRemoteAddr()).thenReturn("10.0.0.1");

        assertEquals("203.0.113.1", trustedLimiter.resolveClientKey(req));
    }

    @Test
    public void chatStreamEndpointUsesSeparateCounters() {
        StreamRequestLimiter.Config separateConfig = new StreamRequestLimiter.Config();
        separateConfig.enabled = true;
        separateConfig.agentAsk = new StreamRequestLimiter.EndpointLimit(2, 1, 5, 60);
        separateConfig.chatStream = new StreamRequestLimiter.EndpointLimit(1, 1, 5, 60);
        StreamRequestLimiter separateLimiter = new StreamRequestLimiter(separateConfig);

        HttpServletRequest req = mockRequest("10.0.0.1");
        String key = separateLimiter.resolveClientKey(req);

        StreamRequestLimiter.Lease agentLease = separateLimiter.tryAcquire(key, "agent-ask");
        assertTrue(agentLease.isAllowed());

        StreamRequestLimiter.Lease chatLease = separateLimiter.tryAcquire(key, "chat-stream");
        assertTrue("chat-stream has separate global counter", chatLease.isAllowed());

        agentLease.release();
        chatLease.release();
    }

    private static HttpServletRequest mockRequest(String remoteAddr) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn(remoteAddr);
        return req;
    }
}
