package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt;
import cn.lunalhx.ai.domain.tool.model.EvidenceObservationType;
import cn.lunalhx.ai.domain.tool.model.EvidenceRevalidation;

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
        EvidenceRevalidation rule = receipt == null ? null : receipt.getRevalidation();
        if (workspaceRoot == null || receipt == null || !receipt.isRevalidatable()
                || rule.getObservationType() != EvidenceObservationType.SEARCH
                || !rule.getToolSemantics().startsWith("search:rg:")) {
            return false;
        }
        try {
            Path root = workspaceRoot.toRealPath();
            Path scope = root.resolve(rule.getSearchScope()).normalize().toRealPath();
            if (!scope.startsWith(root)
                    || (!Files.isDirectory(scope) && !Files.isRegularFile(scope))) {
                return false;
            }
            SearchObservationService.Observation observation = OBSERVATIONS.observe(
                    root, scope, rule.getNormalizedQuery());
            return receipt.getNormalizedScope().equals(observation.normalizedScope())
                    && rule.getSearchScope().equals(observation.searchScope())
                    && rule.getEngineVersion().equals(observation.engineVersion())
                    && rule.getToolSemantics().equals(observation.toolSemantics())
                    && receipt.getStateDigest().equals(observation.stateDigest());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
