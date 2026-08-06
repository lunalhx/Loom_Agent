package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.memory.adapter.port.DurableMemoryRepository;
import cn.lunalhx.ai.domain.memory.model.MemoryEntry;
import cn.lunalhx.ai.domain.memory.service.MemoryPromotionService;
import cn.lunalhx.ai.infrastructure.store.FileDurableMemoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

/**
 * Durable memory: explicit promotion, no-intent rejection, sensitive content
 * rejection, same-subject replacement, and cross-workspace isolation.
 */
public class DurableMemoryTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private DurableMemoryRepository repo(Path workspace) {
        return new FileDurableMemoryRepository(workspace, mapper);
    }

    @Test
    public void explicitIntentPromotesStructuredConclusion() throws Exception {
        Path ws = Files.createTempDirectory("mem-explicit");
        MemoryPromotionService svc = new MemoryPromotionService(repo(ws));
        Optional<MemoryEntry> saved = svc.promote(
                "请记住：本项目使用 Maven 管理依赖",
                "本项目使用 Maven 管理依赖。",
                "run_1");
        assertTrue(saved.isPresent());
        assertEquals("dependency_facts", saved.get().getTopic());
        assertEquals("run_1", saved.get().getSourceRunId());

        Path index = ws.resolve(".loom-code").resolve("memory").resolve("index.json");
        assertTrue(Files.isRegularFile(index));
        Path topic = ws.resolve(".loom-code").resolve("memory").resolve("topics")
                .resolve("dependency_facts.json");
        assertTrue(Files.isRegularFile(topic));
    }

    @Test
    public void noIntentIsNotPromoted() throws Exception {
        Path ws = Files.createTempDirectory("mem-no-intent");
        MemoryPromotionService svc = new MemoryPromotionService(repo(ws));
        Optional<MemoryEntry> saved = svc.promote(
                "帮我看看 pom.xml",
                "pom.xml 使用 Maven 管理依赖。",
                "run_1");
        assertTrue(saved.isEmpty());
    }

    @Test
    public void secretShapedAnswerIsRejected() throws Exception {
        Path ws = Files.createTempDirectory("mem-secret");
        MemoryPromotionService svc = new MemoryPromotionService(repo(ws));
        Optional<MemoryEntry> saved = svc.promote(
                "记住这个密码",
                "我的密码是 sk-abcdef1234567890。",
                "run_1");
        assertTrue(saved.isEmpty());
    }

    @Test
    public void logShapedAnswerIsRejected() throws Exception {
        Path ws = Files.createTempDirectory("mem-log");
        MemoryPromotionService svc = new MemoryPromotionService(repo(ws));
        Optional<MemoryEntry> saved = svc.promote(
                "记住这个输出",
                "stdout:\nline1\nline2",
                "run_1");
        assertTrue(saved.isEmpty());
    }

    @Test
    public void sameSubjectNewConclusionReplacesOld() throws Exception {
        Path ws = Files.createTempDirectory("mem-replace");
        DurableMemoryRepository repo = repo(ws);
        MemoryPromotionService svc = new MemoryPromotionService(repo);
        svc.promote("记住：日志框架使用 Log4j2", "本项目日志框架使用 Log4j2。", "run_1");
        svc.promote("记住：日志框架改用 Slf4j", "本项目日志框架改用 Slf4j。", "run_2");

        List<MemoryEntry> all = repo.findAll();
        assertEquals(1, all.size());
        assertTrue("new conclusion must replace the old one",
                all.get(0).getContent().contains("Slf4j"));
        assertFalse(all.get(0).getContent().contains("Log4j2"));
    }

    @Test
    public void workspacesAreIsolated() throws Exception {
        Path wsA = Files.createTempDirectory("mem-ws-a");
        Path wsB = Files.createTempDirectory("mem-ws-b");
        MemoryPromotionService svcA = new MemoryPromotionService(repo(wsA));
        svcA.promote("记住：使用 Maven", "本项目使用 Maven。", "run_1");

        assertFalse(repo(wsA).findAll().isEmpty());
        assertTrue(repo(wsB).findAll().isEmpty());
    }
}
