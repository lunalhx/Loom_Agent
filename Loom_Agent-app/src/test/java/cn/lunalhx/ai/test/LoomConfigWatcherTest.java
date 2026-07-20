package cn.lunalhx.ai.test;

import cn.lunalhx.ai.config.LoomConfigWatcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LoomConfigWatcherTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void continuousWritePublishesOnlyTheStableFile() throws Exception {
        Path file = temporaryFolder.newFile("runtime.yml").toPath();
        Files.writeString(file, "version: 1");
        AtomicInteger reloads = new AtomicInteger();
        CountDownLatch reloaded = new CountDownLatch(1);
        LoomConfigWatcher watcher = new LoomConfigWatcher();
        watcher.register(file, () -> {
            if ("version: 2".equals(read(file))) {
                reloads.incrementAndGet();
                reloaded.countDown();
            }
        });
        watcher.start();
        try {
            Files.writeString(file, "version:");
            Thread.sleep(100);
            Files.writeString(file, "version: 2");
            assertTrue(reloaded.await(2, TimeUnit.SECONDS));
            assertEquals(1, reloads.get());
        } finally {
            watcher.stop();
        }
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
