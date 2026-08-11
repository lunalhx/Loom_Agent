package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt;
import cn.lunalhx.ai.domain.tool.model.EvidenceObservationType;
import cn.lunalhx.ai.domain.tool.model.EvidenceRevalidation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Trusted revalidator for complete {@code list_files} observations. */
public final class ListFilesEvidenceVerifier {

    private static final ListFilesObservationService OBSERVATIONS =
            new ListFilesObservationService();

    private ListFilesEvidenceVerifier() {
    }

    public static boolean matches(Path workspaceRoot, EvidenceReceipt receipt) {
        EvidenceRevalidation rule = receipt == null ? null : receipt.getRevalidation();
        if (workspaceRoot == null || receipt == null || !receipt.isRevalidatable()
                || rule.getObservationType() != EvidenceObservationType.LIST_FILES
                || !ListFilesObservationService.TOOL_SEMANTICS.equals(rule.getToolSemantics())) {
            return false;
        }
        try {
            Path root = workspaceRoot.toRealPath();
            Path directory = root.resolve(rule.getRepositoryRelativePath())
                    .normalize().toRealPath();
            if (!directory.startsWith(root) || !Files.isDirectory(directory)) {
                return false;
            }
            ListFilesObservationService.Observation observation = OBSERVATIONS.observe(root, directory);
            return receipt.getNormalizedScope().equals(observation.normalizedScope())
                    && receipt.getStateDigest().equals(observation.stateDigest());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
