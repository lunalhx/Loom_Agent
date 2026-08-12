package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt;
import cn.lunalhx.ai.domain.tool.model.EvidenceObservationType;
import cn.lunalhx.ai.domain.tool.model.EvidenceRevalidation;
import cn.lunalhx.ai.domain.tool.service.RepositoryStateTracker;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RepositoryEvidenceVerifierTest {

    @Test
    public void repositoryReceiptTracksActualRepositoryStateRatherThanShellOutput() throws Exception {
        Path workspace = Files.createTempDirectory("repository-evidence").toRealPath();
        Files.writeString(workspace.resolve("readme.txt"), "before");
        EvidenceReceipt receipt = EvidenceReceipt.builder()
                .evidenceKey("run_shell|repository")
                .normalizedScope("repository:.")
                .stateDigest(RepositoryStateTracker.stableFingerprint(workspace))
                .complete(true)
                .sourceRunId("run")
                .rootRunId("run")
                .revalidation(EvidenceRevalidation.builder()
                        .digestAlgorithm("SHA-256")
                        .observationType(EvidenceObservationType.REPOSITORY)
                        .toolSemantics("shell:repository:v1")
                        .repositoryRelativePath(".")
                        .build())
                .build();

        assertTrue(PlanEvidenceVerifier.matches(workspace, receipt));
        Files.writeString(workspace.resolve("readme.txt"), "after");
        assertFalse(PlanEvidenceVerifier.matches(workspace, receipt));
    }
}
