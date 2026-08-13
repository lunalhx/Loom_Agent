package cn.lunalhx.ai.infrastructure.store;

import cn.lunalhx.ai.domain.agent.model.entity.AttemptLease;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Repository contract: a healthy Attempt owner cannot be taken over, and an
 * expired or released owner is fenced from later writes after a new Attempt
 * starts. TTL and fence representation are not part of the public contract.
 */
public class FileAttemptLeaseRepositoryTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void healthyOwnerCannotBeTakenOver() throws Exception {
        Path workspace = Files.createTempDirectory("lease-healthy");
        MutableClock clock = new MutableClock(Instant.parse("2026-08-13T00:00:00Z"));
        FileAttemptLeaseRepository first = repository(workspace, clock);
        FileAttemptLeaseRepository second = repository(workspace, clock);

        AttemptLease owned = first.tryAcquire("run-a", "attempt-1").orElseThrow();
        assertTrue(first.heartbeat("run-a", owned.getFence()));
        assertTrue(first.isHealthy("run-a"));

        assertTrue(second.tryAcquire("run-a", "attempt-2").isEmpty());
        assertEquals(owned.getFence(), first.find("run-a").orElseThrow().getFence());
        first.requireWritable("run-a", owned.getFence());
    }

    @Test
    public void concurrentAcquireAllowsOnlyOneHealthyOwner() throws Exception {
        Path workspace = Files.createTempDirectory("lease-race");
        FileAttemptLeaseRepository first = repository(workspace, Clock.systemUTC());
        FileAttemptLeaseRepository second = repository(workspace, Clock.systemUTC());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<AttemptLease>> one = executor.submit(() -> {
                ready.countDown();
                start.await();
                Optional<AttemptLease> lease = first.tryAcquire("run-race", "attempt-1");
                if (lease.isPresent()) {
                    wins.incrementAndGet();
                }
                return lease;
            });
            Future<Optional<AttemptLease>> two = executor.submit(() -> {
                ready.countDown();
                start.await();
                Optional<AttemptLease> lease = second.tryAcquire("run-race", "attempt-2");
                if (lease.isPresent()) {
                    wins.incrementAndGet();
                }
                return lease;
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            Optional<AttemptLease> a = one.get(5, TimeUnit.SECONDS);
            Optional<AttemptLease> b = two.get(5, TimeUnit.SECONDS);
            assertEquals(1, wins.get());
            assertTrue(a.isPresent() ^ b.isPresent());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void expiredOwnerCannotWriteAfterNewAttemptStarts() throws Exception {
        Path workspace = Files.createTempDirectory("lease-expired");
        MutableClock clock = new MutableClock(Instant.parse("2026-08-13T00:00:00Z"));
        FileAttemptLeaseRepository stale = repository(workspace, clock);
        FileAttemptLeaseRepository recovered = repository(workspace, clock);

        AttemptLease first = stale.tryAcquire("run-b", "attempt-1").orElseThrow();
        clock.advance(Duration.ofHours(1));
        assertFalse(stale.isHealthy("run-b"));

        AttemptLease next = recovered.tryAcquire("run-b", "attempt-2").orElseThrow();
        assertNotEquals(first.getFence(), next.getFence());
        assertEquals("attempt-2", next.getAttemptId());
        recovered.requireWritable("run-b", next.getFence());

        try {
            stale.requireWritable("run-b", first.getFence());
            fail("expired fence must not write after a new Attempt starts");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("fence"));
        }
        assertFalse(stale.heartbeat("run-b", first.getFence()));
    }

    @Test
    public void releasedOwnerCannotWriteAfterNewAttemptStarts() throws Exception {
        Path workspace = Files.createTempDirectory("lease-released");
        FileAttemptLeaseRepository stale = repository(workspace, Clock.systemUTC());
        FileAttemptLeaseRepository recovered = repository(workspace, Clock.systemUTC());

        AttemptLease first = stale.tryAcquire("run-c", "attempt-1").orElseThrow();
        assertTrue(stale.release("run-c", first.getFence()));
        assertFalse(stale.isHealthy("run-c"));

        AttemptLease next = recovered.tryAcquire("run-c", "attempt-2").orElseThrow();
        assertNotEquals(first.getFence(), next.getFence());
        recovered.requireWritable("run-c", next.getFence());
        try {
            stale.requireWritable("run-c", first.getFence());
            fail("released fence must not write after a new Attempt starts");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("fence"));
        }
    }

    private FileAttemptLeaseRepository repository(Path workspace, Clock clock) {
        return new FileAttemptLeaseRepository(workspace, mapper, clock);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
