package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt;
import cn.lunalhx.ai.infrastructure.loom.ListFilesEvidenceVerifier;
import cn.lunalhx.ai.infrastructure.loom.ReadFileEvidenceVerifier;
import cn.lunalhx.ai.infrastructure.loom.SearchEvidenceVerifier;

import java.nio.file.Path;

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
}
