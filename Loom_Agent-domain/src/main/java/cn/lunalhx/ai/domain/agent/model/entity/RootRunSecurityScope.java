package cn.lunalhx.ai.domain.agent.model.entity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Transient root-run ownership for disposable files, one Shell permit, and cancellation. */
public final class RootRunSecurityScope implements AutoCloseable {
    private final Path homeRoot;
    private final Path temporaryRoot;
    private final Semaphore shellPermit = new Semaphore(1, true);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Set<Runnable> shellCancellers = ConcurrentHashMap.newKeySet();

    private RootRunSecurityScope(Path homeRoot, Path temporaryRoot) {
        this.homeRoot = homeRoot;
        this.temporaryRoot = temporaryRoot;
    }

    public static RootRunSecurityScope create() {
        try {
            return new RootRunSecurityScope(Files.createTempDirectory("loom-home-"),
                    Files.createTempDirectory("loom-tmp-"));
        } catch (IOException e) { throw new IllegalStateException("cannot create disposable run roots", e); }
    }

    public Path homeRoot() { return homeRoot; }
    public Path temporaryRoot() { return temporaryRoot; }
    public boolean isCancelled() { return cancelled.get(); }

    public ShellLease acquireShell() throws InterruptedException {
        shellPermit.acquire();
        if (cancelled.get()) { shellPermit.release(); throw new InterruptedException("run cancelled"); }
        return new ShellLease();
    }

    public void registerShellCanceller(Runnable canceller) {
        if (cancelled.get()) { canceller.run(); return; }
        shellCancellers.add(canceller);
        if (cancelled.get() && shellCancellers.remove(canceller)) canceller.run();
    }
    public void unregisterShellCanceller(Runnable canceller) { shellCancellers.remove(canceller); }
    public void cancel() { if (cancelled.compareAndSet(false, true)) shellCancellers.forEach(Runnable::run); }

    @Override public void close() {
        cancel();
        delete(homeRoot);
        delete(temporaryRoot);
    }

    private static void delete(Path root) {
        try {
            if (root == null || !Files.exists(root)) return;
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file); return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                    if (error != null) throw error; Files.deleteIfExists(dir); return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) { }
    }

    public final class ShellLease implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        @Override public void close() { if (closed.compareAndSet(false, true)) shellPermit.release(); }
    }
}
