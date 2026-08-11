package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Trusted revalidator for complete {@code search} observations. */
public final class SearchEvidenceVerifier {

    private static final SearchObservationService OBSERVATIONS =
            new SearchObservationService();

    private SearchEvidenceVerifier() {
    }

    public static boolean matches(Path workspaceRoot, EvidenceReceipt receipt) {
        if (workspaceRoot == null || receipt == null || !receipt.isRevalidatable()
                || !SearchObservationService.OBSERVATION_TYPE.equals(receipt.getObservationType())
                || receipt.getToolSemantics() == null
                || !receipt.getToolSemantics().startsWith("search:rg:")) {
            return false;
        }
        try {
            Path root = workspaceRoot.toRealPath();
            Path scope = root.resolve(receipt.getSearchScope()).normalize().toRealPath();
            if (!scope.startsWith(root)
                    || (!Files.isDirectory(scope) && !Files.isRegularFile(scope))) {
                return false;
            }
            SearchObservationService.Observation observation = OBSERVATIONS.observe(
                    root, scope, receipt.getNormalizedQuery());
            return receipt.getNormalizedScope().equals(observation.normalizedScope())
                    && receipt.getSearchScope().equals(observation.searchScope())
                    && receipt.getEngineVersion().equals(observation.engineVersion())
                    && receipt.getToolSemantics().equals(observation.toolSemantics())
                    && receipt.getStateDigest().equals(observation.stateDigest())
                    && receipt.getRevalidation().matches(
                    SearchObservationService.OBSERVATION_TYPE,
                    observation.toolSemantics(), receipt.getRepositoryRelativePath(), null, null,
                    receipt.getNormalizedQuery(), receipt.getSearchScope(), receipt.getEngineVersion());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
