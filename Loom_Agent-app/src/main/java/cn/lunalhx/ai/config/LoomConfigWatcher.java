package cn.lunalhx.ai.config;

import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class LoomConfigWatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LoomConfigWatcher.class);

    private final List<Target> targets = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "loom-config-watcher");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean running;

    public void register(Path path, Runnable reload) {
        targets.add(new Target(path.toAbsolutePath().normalize(), reload, fingerprint(path)));
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        executor.scheduleWithFixedDelay(this::poll, 250, 250, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        running = false;
        executor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void poll() {
        for (Target target : targets) {
            try {
                target.poll();
            } catch (Exception e) {
                log.warn("Configuration reload rejected for {}: {}", target.path, safeMessage(e));
            }
        }
    }

    private static String fingerprint(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return "missing";
            }
            return DigestUtils.sha256Hex(Files.readAllBytes(path));
        } catch (IOException e) {
            return "unreadable";
        }
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName()
                : message.replaceAll("(?i)(api[-_ ]?key|authorization|token)\\s*[:=]\\s*\\S+", "$1=<redacted>");
    }

    private static final class Target {
        private final Path path;
        private final Runnable reload;
        private String committed;
        private String observed;
        private int stablePolls;

        private Target(Path path, Runnable reload, String initial) {
            this.path = path;
            this.reload = reload;
            this.committed = initial;
            this.observed = initial;
        }

        private void poll() {
            String current = fingerprint(path);
            if (current.equals(committed) || "missing".equals(current) || "unreadable".equals(current)) {
                observed = current;
                stablePolls = 0;
                return;
            }
            if (!current.equals(observed)) {
                observed = current;
                stablePolls = 1;
                return;
            }
            if (++stablePolls < 2) {
                return;
            }
            reload.run();
            committed = current;
            stablePolls = 0;
        }
    }
}
