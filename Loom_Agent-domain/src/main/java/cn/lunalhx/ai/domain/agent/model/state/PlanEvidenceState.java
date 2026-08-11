package cn.lunalhx.ai.domain.agent.model.state;

import cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Candidate receipts and irreversible same-Run drift for one Run context.
 * Receipts with the same key and digest are idempotent; a different digest
 * adds a receipt and permanently marks drift.
 */
public final class PlanEvidenceState {

    private final List<EvidenceReceipt> receipts = new ArrayList<>();
    private boolean drift;

    public List<EvidenceReceipt> receipts() {
        return List.copyOf(receipts);
    }

    public boolean drift() {
        return drift;
    }

    public void record(EvidenceReceipt receipt) {
        if (receipt == null || !receipt.isRevalidatable()) {
            return;
        }
        boolean sameKey = false;
        for (EvidenceReceipt existing : receipts) {
            if (!java.util.Objects.equals(existing.getEvidenceKey(), receipt.getEvidenceKey())) {
                continue;
            }
            sameKey = true;
            if (java.util.Objects.equals(existing.getStateDigest(), receipt.getStateDigest())) {
                return;
            }
        }
        if (sameKey) {
            drift = true;
        }
        receipts.add(receipt);
    }

    public void restore(Collection<EvidenceReceipt> restored, boolean drift) {
        receipts.clear();
        if (restored != null) {
            for (EvidenceReceipt receipt : restored) {
                if (receipt != null && receipt.isRevalidatable()) {
                    receipts.add(receipt);
                }
            }
        }
        this.drift = drift;
    }
}
