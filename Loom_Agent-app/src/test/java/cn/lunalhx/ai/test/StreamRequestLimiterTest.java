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

    private StreamRequestLimiter limiter;

    @Before
    public void setUp() {
        StreamRequestLimiter.Config config = new StreamRequestLimiter.Config();
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
            StreamRequestLimiter.Lease lease = limiter.tryAcquire(key);
            assertTrue("request " + i + " should be allowed", lease.isAllowed());
            lease.release();
        }

        StreamRequestLimiter.Lease rejected = limiter.tryAcquire(key);
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

        StreamRequestLimiter.Lease l1 = limiter.tryAcquire(key1);
        StreamRequestLimiter.Lease l2 = limiter.tryAcquire(key2);
        assertTrue(l1.isAllowed());
        assertTrue(l2.isAllowed());

        StreamRequestLimiter.Lease l3 = limiter.tryAcquire(key3);
        assertFalse(l3.isAllowed());
        assertTrue(l3.isConcurrencyLimited());

        l1.release();
        StreamRequestLimiter.Lease l4 = limiter.tryAcquire(key3);
        assertTrue(l4.isAllowed());
        l2.release();
        l4.release();
    }

    @Test
    public void resolveClientKeyDefaultUsesRemoteAddr() {
        HttpServletRequest req = mockRequest("192.168.1.1");
        assertEquals("192.168.1.1", limiter.resolveClientKey(req));
    }

    private static HttpServletRequest mockRequest(String remoteAddr) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn(remoteAddr);
        return req;
    }
}
