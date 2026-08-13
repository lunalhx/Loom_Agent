package cn.lunalhx.ai.infrastructure.store;

import cn.lunalhx.ai.domain.agent.adapter.port.AttemptLeaseRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AttemptLease;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.FileLockInterruptionException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * File-backed {@link AttemptLease} under
 * {@code .loom-code/runs/<runId>/attempt-lease.json}.
 */
public final class FileAttemptLeaseRepository implements AttemptLeaseRepository {

    static final Duration TTL = Duration.ofSeconds(30);

    private final Path root;
    private final ObjectMapper mapper;
    private final Clock clock;
    private static final ConcurrentMap<Path, ReentrantLock> LEASE_LOCKS = new ConcurrentHashMap<>();

    public FileAttemptLeaseRepository(Path workspaceRoot, ObjectMapper mapper) {
        this(workspaceRoot, mapper, Clock.systemUTC());
    }

    public FileAttemptLeaseRepository(Path workspaceRoot, ObjectMapper mapper, Clock clock) {
        this.root = workspaceRoot.resolve(".loom-code").resolve("runs");
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public Optional<AttemptLease> find(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        return withLock(runId, () -> readUnlocked(runId));
    }

    @Override
    public Optional<AttemptLease> tryAcquire(String runId, String attemptId) {
        if (runId == null || runId.isBlank() || attemptId == null || attemptId.isBlank()) {
            return Optional.empty();
        }
        return withLock(runId, () -> {
            Optional<AttemptLease> current = readUnlocked(runId);
            if (current.isPresent() && healthy(current.get())) {
                return Optional.empty();
            }
            AttemptLease lease = AttemptLease.builder()
                    .runId(runId)
                    .attemptId(attemptId)
                    .fence(UUID.randomUUID().toString())
                    .heartbeatEpochMilli(clock.millis())
                    .released(false)
                    .build();
            writeUnlocked(lease);
            return Optional.of(lease);
        });
    }

    @Override
    public boolean heartbeat(String runId, String fence) {
        if (runId == null || fence == null) {
            return false;
        }
        return withLock(runId, () -> {
            Optional<AttemptLease> current = readUnlocked(runId);
            if (current.isEmpty() || !writable(current.get(), fence)) {
                return false;
            }
            current.get().setHeartbeatEpochMilli(clock.millis());
            writeUnlocked(current.get());
            return true;
        });
    }

    @Override
    public boolean release(String runId, String fence) {
        if (runId == null || fence == null) {
            return false;
        }
        return withLock(runId, () -> {
            Optional<AttemptLease> current = readUnlocked(runId);
            if (current.isEmpty() || !fence.equals(current.get().getFence())) {
                return false;
            }
            current.get().setReleased(true);
            writeUnlocked(current.get());
            return true;
        });
    }

    @Override
    public boolean isHealthy(String runId) {
        return find(runId).filter(this::healthy).isPresent();
    }

    @Override
    public void requireWritable(String runId, String fence) {
        requireWritableAndRenew(runId, fence, false);
    }

    @Override
    public void requireWritableAndRenew(String runId, String fence) {
        requireWritableAndRenew(runId, fence, true);
    }

    private void requireWritableAndRenew(String runId, String fence, boolean renew) {
        if (runId == null || fence == null) {
            throw new IllegalStateException("attempt lease fence is missing");
        }
        withLock(runId, () -> {
            Optional<AttemptLease> current = readUnlocked(runId);
            if (current.isEmpty() || !writable(current.get(), fence)) {
                throw new IllegalStateException(
                        "attempt lease fence cannot write run " + runId);
            }
            if (renew) {
                current.get().setHeartbeatEpochMilli(clock.millis());
                writeUnlocked(current.get());
            }
            return true;
        });
    }

    private boolean healthy(AttemptLease lease) {
        return lease != null
                && !lease.isReleased()
                && lease.getHeartbeatEpochMilli() > 0
                && clock.millis() <= lease.getHeartbeatEpochMilli() + TTL.toMillis();
    }

    private boolean writable(AttemptLease lease, String fence) {
        return healthy(lease) && fence.equals(lease.getFence());
    }

    private Optional<AttemptLease> readUnlocked(String runId) {
        Path target = leasePath(runId);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(target.toFile(), AttemptLease.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private void writeUnlocked(AttemptLease lease) {
        try {
            Path target = leasePath(lease.getRunId());
            Files.createDirectories(target.getParent());
            AtomicFiles.write(target,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(lease));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "cannot save attempt lease for " + lease.getRunId() + ": " + e.getMessage(), e);
        }
    }

    private Path leasePath(String runId) {
        return root.resolve(runId).resolve("attempt-lease.json");
    }

    private Path lockPath(String runId) {
        return root.resolve(runId).resolve("attempt-lease.lock");
    }

    private <T> T withLock(String runId, Locked<T> action) {
        ReentrantLock lock = LEASE_LOCKS.computeIfAbsent(
                leasePath(runId).toAbsolutePath().normalize(), ignored -> new ReentrantLock());
        lock.lock();
        try {
            Files.createDirectories(root.resolve(runId));
            try (FileChannel channel = FileChannel.open(lockPath(runId),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return action.run();
            }
        } catch (FileLockInterruptionException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cannot lock attempt lease " + runId + ": interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "cannot lock attempt lease " + runId + ": " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    @FunctionalInterface
    private interface Locked<T> {
        T run() throws IOException;
    }
}
