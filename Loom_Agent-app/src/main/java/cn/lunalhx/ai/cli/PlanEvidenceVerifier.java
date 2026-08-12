package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt;
import cn.lunalhx.ai.domain.agent.model.entity.Plan;
import cn.lunalhx.ai.infrastructure.loom.ListFilesEvidenceVerifier;
import cn.lunalhx.ai.infrastructure.loom.ReadFileEvidenceVerifier;
import cn.lunalhx.ai.infrastructure.loom.SearchEvidenceVerifier;
import cn.lunalhx.ai.infrastructure.loom.RepositoryEvidenceVerifier;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Trusted adapter for recomputing the freshness of persisted Plan Basis. */
public final class PlanEvidenceVerifier {

    private static final EvidenceVerifierRegistry DEFAULT = new EvidenceVerifierRegistry(Map.of(
            cn.lunalhx.ai.domain.tool.model.EvidenceObservationType.READ_FILE, ReadFileEvidenceVerifier::matches,
            cn.lunalhx.ai.domain.tool.model.EvidenceObservationType.LIST_FILES, ListFilesEvidenceVerifier::matches,
            cn.lunalhx.ai.domain.tool.model.EvidenceObservationType.SEARCH, SearchEvidenceVerifier::matches,
            cn.lunalhx.ai.domain.tool.model.EvidenceObservationType.GIT, RepositoryEvidenceVerifier::matches,
            cn.lunalhx.ai.domain.tool.model.EvidenceObservationType.REPOSITORY, RepositoryEvidenceVerifier::matches));

    private PlanEvidenceVerifier() {
    }

    public static boolean matches(Path workspaceRoot, EvidenceReceipt receipt) {
        if (receipt == null || !receipt.isRevalidatable()) {
            return false;
        }
        return DEFAULT.matches(workspaceRoot, receipt);
    }

    public static boolean matchesAll(Path workspaceRoot, List<EvidenceReceipt> receipts,
                                     String rootRunId) {
        if (receipts == null || receipts.isEmpty()) {
            return true;
        }
        for (EvidenceReceipt receipt : receipts) {
            if (receipt == null || receipt.getSourceRunId() == null
                    || receipt.getSourceRunId().isBlank()
                    || (rootRunId != null && !rootRunId.isBlank()
                    && !Objects.equals(receipt.getRootRunId(), rootRunId))
                    || !matches(workspaceRoot, receipt)) {
                return false;
            }
        }
        return true;
    }

    public static boolean matchesPlan(Path workspaceRoot, Plan plan) {
        return plan != null && plan.currentRevision() != null
                && matchesAll(workspaceRoot, plan.currentRevision().getPlanBasis(), null);
    }
}
