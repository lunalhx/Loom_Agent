package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt;
import cn.lunalhx.ai.domain.tool.model.EvidenceObservationType;

import java.nio.file.Path;
import java.util.Map;

/** Injectable dispatch table for trusted Plan evidence revalidation. */
public final class EvidenceVerifierRegistry {

    @FunctionalInterface
    public interface Verifier {
        boolean matches(Path workspaceRoot, EvidenceReceipt receipt);
    }

    private final Map<EvidenceObservationType, Verifier> verifiers;

    public EvidenceVerifierRegistry(Map<EvidenceObservationType, Verifier> verifiers) {
        this.verifiers = Map.copyOf(verifiers);
    }

    public boolean matches(Path workspaceRoot, EvidenceReceipt receipt) {
        if (receipt == null || receipt.getRevalidation() == null) return false;
        Verifier verifier = verifiers.get(receipt.getRevalidation().getObservationType());
        return verifier != null && verifier.matches(workspaceRoot, receipt);
    }
}
