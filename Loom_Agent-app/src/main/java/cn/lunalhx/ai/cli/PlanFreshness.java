package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.model.entity.PlanRevision;

import java.nio.file.Path;

/** Read-only freshness computation for Plan list/show controls. */
public final class PlanFreshness {

    private PlanFreshness() {
    }

    public static boolean isFresh(Path workspaceRoot, PlanRevision revision) {
        if (revision == null || revision.getPlanBasis() == null) {
            return false;
        }
        return PlanEvidenceVerifier.matchesAll(workspaceRoot,
                revision.getPlanBasis(), null);
    }
}
