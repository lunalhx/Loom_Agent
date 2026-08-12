package cn.lunalhx.ai.infrastructure.loom;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ShellEvidenceObservationServiceTest {

    private final ShellEvidenceObservationService observations = new ShellEvidenceObservationService();

    @Test
    public void commonReadOnlyFormsProducePreciseReceiptsAndUnsupportedFormsFallBack() throws Exception {
        Path workspace = Files.createTempDirectory("shell-evidence").toRealPath();
        Files.writeString(workspace.resolve("readme.txt"), "hello\nworld\n");
        Files.createDirectory(workspace.resolve("docs"));

        var files = observations.observe(workspace, "cat readme.txt && ls docs");
        assertEquals(2, files.size());
        assertTrue(files.stream().anyMatch(candidate -> candidate.getEvidenceKey().startsWith("shell:read_file|")));
        assertTrue(files.stream().anyMatch(candidate -> candidate.getEvidenceKey().startsWith("shell:list_files|")));

        var fallback = observations.observe(workspace, "head -n 1 readme.txt");
        assertEquals("run_shell|repository", fallback.getFirst().getEvidenceKey());
    }
}
