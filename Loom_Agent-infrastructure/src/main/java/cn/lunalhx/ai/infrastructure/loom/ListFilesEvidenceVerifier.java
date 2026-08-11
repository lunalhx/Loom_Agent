package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt;

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
        if (workspaceRoot == null || receipt == null || !receipt.isRevalidatable()
                || !ListFilesObservationService.OBSERVATION_TYPE.equals(receipt.getObservationType())
                || !ListFilesObservationService.TOOL_SEMANTICS.equals(receipt.getToolSemantics())) {
            return false;
        }
        try {
            Path root = workspaceRoot.toRealPath();
            Path directory = root.resolve(receipt.getRepositoryRelativePath())
                    .normalize().toRealPath();
            if (!directory.startsWith(root) || !Files.isDirectory(directory)) {
                return false;
            }
            ListFilesObservationService.Observation observation = OBSERVATIONS.observe(root, directory);
            return receipt.getNormalizedScope().equals(observation.normalizedScope())
                    && receipt.getStateDigest().equals(observation.stateDigest())
                    && receipt.getRevalidation().matches(
                    ListFilesObservationService.OBSERVATION_TYPE,
                    ListFilesObservationService.TOOL_SEMANTICS,
                    receipt.getRepositoryRelativePath(), null, null, null, null, null);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
