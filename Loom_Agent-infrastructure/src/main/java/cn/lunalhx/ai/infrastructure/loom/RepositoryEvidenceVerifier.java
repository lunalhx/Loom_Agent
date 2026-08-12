package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt;
import cn.lunalhx.ai.domain.tool.model.EvidenceObservationType;
import cn.lunalhx.ai.domain.tool.model.EvidenceRevalidation;
import cn.lunalhx.ai.domain.tool.service.RepositoryStateTracker;

import java.nio.file.Path;

/** Revalidates a whole-repository or Git logical-state observation without reading Shell output. */
public final class RepositoryEvidenceVerifier {

    private RepositoryEvidenceVerifier() {
    }

    public static boolean matches(Path workspaceRoot, EvidenceReceipt receipt) {
        EvidenceRevalidation rule = receipt == null ? null : receipt.getRevalidation();
        if (workspaceRoot == null || receipt == null || !receipt.isRevalidatable() || rule == null
                || (rule.getObservationType() != EvidenceObservationType.REPOSITORY
                && rule.getObservationType() != EvidenceObservationType.GIT)
                || !".".equals(rule.getRepositoryRelativePath())
                || !("shell:repository:v1".equals(rule.getToolSemantics())
                || "shell:git:v1".equals(rule.getToolSemantics()))) {
            return false;
        }
        try {
            Path root = workspaceRoot.toRealPath();
            return receipt.getStateDigest().equals(RepositoryStateTracker.stableFingerprint(root));
        } catch (Exception ignored) {
            return false;
        }
    }
}
