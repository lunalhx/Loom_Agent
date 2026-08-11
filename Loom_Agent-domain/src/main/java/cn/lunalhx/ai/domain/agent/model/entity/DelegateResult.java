package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Safe, structured outcome returned by a delegate. Raw child context and raw
 * tool observations are intentionally absent; receipts are safe metadata only.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DelegateResult {

    private String safeOutcome;
    private AgentRunStatus status;
    private DelegateProvenance provenance;
    @Builder.Default
    private List<EvidenceReceipt> evidenceReceipts = List.of();
    private String errorCode;

    @JsonIgnore
    public boolean isSuccessful() {
        return status == AgentRunStatus.COMPLETED;
    }

    public List<EvidenceReceipt> safeEvidenceReceipts() {
        if (evidenceReceipts == null) {
            return List.of();
        }
        return evidenceReceipts.stream()
                .filter(receipt -> receipt != null && receipt.isRevalidatable())
                .toList();
    }
}
