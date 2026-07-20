package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.common.LoomPaths;
import cn.lunalhx.ai.domain.tool.sandbox.Sandbox;
import cn.lunalhx.ai.domain.tool.sandbox.SandboxLease;
import cn.lunalhx.ai.domain.tool.sandbox.SandboxProvider;
import cn.lunalhx.ai.domain.tool.sandbox.SandboxRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;

public final class LocalSandboxProvider implements SandboxProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalSandboxProvider.class);

    private final BackgroundProcessManager processManager;
    private final LoomPaths paths;
    private final int maxCachedConversations;
    private final long idleTtlMs;
    private final Map<String, Entry> sandboxes = new ConcurrentHashMap<>();
    private final Set<String> pendingCleanup = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "local-sandbox-cleaner");
        thread.setDaemon(true);
        return thread;
    });

    public LocalSandboxProvider(BackgroundProcessManager processManager,
                                LoomPaths paths,
                                int maxCachedConversations,
                                long idleTtlMs) {
        this.processManager = processManager;
        this.paths = paths;
        this.maxCachedConversations = maxCachedConversations;
        this.idleTtlMs = idleTtlMs;
        cleaner.scheduleWithFixedDelay(this::cleanupIdle, 60, 60, TimeUnit.SECONDS);
    }

    @Override
    public SandboxLease acquire(SandboxRequest request) {
        Entry entry = sandboxes.compute(request.conversationId(), (id, existing) -> {
            if (existing != null) {
                if (!existing.workspace.equals(request.workspace().toAbsolutePath().normalize())) {
                    throw new IllegalStateException("Conversation sandbox workspace cannot change");
                }
                existing.references.incrementAndGet();
                existing.lastUsed = Instant.now();
                return existing;
            }
            try {
                LocalSandbox sandbox = new LocalSandbox(request, processManager, paths.home(),
                        paths.sessionTemp(id), paths.uploads(id), paths.outputs(id));
                return new Entry(request.workspace().toAbsolutePath().normalize(), sandbox);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot create local sandbox: " + e.getMessage(), e);
            }
        });
        cleanupOverflow();
        return new Lease(entry);
    }

    @Override
    public void endConversation(String conversationId) {
        Entry entry = sandboxes.remove(conversationId);
        if (entry == null) {
            return;
        }
        entry.sandbox.cancelProcesses();
        cleanupFiles(conversationId);
    }

    @Override
    public void close() {
        cleaner.shutdownNow();
        for (String conversationId : List.copyOf(sandboxes.keySet())) {
            endConversation(conversationId);
        }
    }

    public int cachedSandboxCount() {
        return sandboxes.size();
    }

    public int referenceCount(String conversationId) {
        Entry entry = sandboxes.get(conversationId);
        return entry == null ? 0 : entry.references.get();
    }

    private void cleanupIdle() {
        List.copyOf(pendingCleanup).forEach(this::cleanupFiles);
        Instant cutoff = Instant.now().minusMillis(idleTtlMs);
        sandboxes.entrySet().stream()
                .filter(entry -> entry.getValue().references.get() == 0)
                .filter(entry -> !entry.getValue().sandbox.hasActiveProcesses())
                .filter(entry -> entry.getValue().lastUsed.isBefore(cutoff))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(this::removeIdle);
        cleanupOverflow();
    }

    private void cleanupOverflow() {
        int excess = sandboxes.size() - maxCachedConversations;
        if (excess <= 0) {
            return;
        }
        List<Map.Entry<String, Entry>> candidates = new ArrayList<>(sandboxes.entrySet());
        candidates.removeIf(entry -> entry.getValue().references.get() > 0
                || entry.getValue().sandbox.hasActiveProcesses());
        candidates.sort(Comparator.comparing(entry -> entry.getValue().lastUsed));
        candidates.stream().limit(excess).map(Map.Entry::getKey).forEach(this::removeIdle);
    }

    private void removeIdle(String conversationId) {
        Entry entry = sandboxes.get(conversationId);
        if (entry == null || entry.references.get() > 0 || entry.sandbox.hasActiveProcesses()) {
            return;
        }
        if (sandboxes.remove(conversationId, entry)) {
            cleanupFiles(conversationId);
        }
    }

    private void cleanupFiles(String conversationId) {
        Path root = paths.conversationRoot(conversationId);
        try {
            if (!Files.exists(root)) {
                pendingCleanup.remove(conversationId);
                return;
            }
            try (var files = Files.walk(root)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new CleanupFailure(e);
                    }
                });
            }
            pendingCleanup.remove(conversationId);
        } catch (Exception e) {
            pendingCleanup.add(conversationId);
            log.warn("Sandbox cleanup failed for conversation {}: {}", conversationId, e.getMessage());
        }
    }

    private final class Lease implements SandboxLease {
        private final Entry entry;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(Entry entry) {
            this.entry = entry;
        }

        @Override
        public Sandbox sandbox() {
            return entry.sandbox;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                entry.references.decrementAndGet();
                entry.lastUsed = Instant.now();
            }
        }
    }

    private static final class Entry {
        private final Path workspace;
        private final LocalSandbox sandbox;
        private final AtomicInteger references = new AtomicInteger(1);
        private volatile Instant lastUsed = Instant.now();

        private Entry(Path workspace, LocalSandbox sandbox) {
            this.workspace = workspace;
            this.sandbox = sandbox;
        }
    }

    private static final class CleanupFailure extends RuntimeException {
        private CleanupFailure(IOException cause) {
            super(cause);
        }
    }
}
