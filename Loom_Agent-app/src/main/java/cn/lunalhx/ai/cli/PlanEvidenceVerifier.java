package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt;
import cn.lunalhx.ai.domain.agent.model.entity.Plan;
import cn.lunalhx.ai.infrastructure.loom.ListFilesEvidenceVerifier;
import cn.lunalhx.ai.infrastructure.loom.ReadFileEvidenceVerifier;
import cn.lunalhx.ai.infrastructure.loom.SearchEvidenceVerifier;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Trusted adapter for recomputing the freshness of persisted Plan Basis. */
public final class PlanEvidenceVerifier {

    private PlanEvidenceVerifier() {
    }

    public static boolean matches(Path workspaceRoot, EvidenceReceipt receipt) {
        if (receipt == null || !receipt.isRevalidatable()) {
            return false;
        }
        return switch (receipt.getRevalidation().getObservationType()) {
            case READ_FILE -> ReadFileEvidenceVerifier.matches(workspaceRoot, receipt);
            case LIST_FILES -> ListFilesEvidenceVerifier.matches(workspaceRoot, receipt);
            case SEARCH -> SearchEvidenceVerifier.matches(workspaceRoot, receipt);
        };
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
