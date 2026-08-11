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
        return switch (receipt.getObservationType()) {
            case "read_file" -> ReadFileEvidenceVerifier.matches(workspaceRoot, receipt);
            case "list_files" -> ListFilesEvidenceVerifier.matches(workspaceRoot, receipt);
            case "search" -> SearchEvidenceVerifier.matches(workspaceRoot, receipt);
            default -> false;
        };
    }
}
