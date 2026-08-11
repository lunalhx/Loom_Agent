package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.tool.model.EvidenceRevalidation;
import cn.lunalhx.ai.domain.tool.model.ToolEvidenceCandidate;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Runtime-owned, safe Plan Evidence for one semantic repository observation.
 * It contains no observed file content; the revalidation tuple is sufficient
 * for a trusted adapter to recompute the digest later.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceReceipt {

    private String evidenceKey;
    private String normalizedScope;
    private String stateDigest;
    @JsonProperty("complete")
    private Boolean complete;
    private String sourceRunId;
    private String rootRunId;
    private EvidenceRevalidation revalidation;

    public static EvidenceReceipt from(ToolEvidenceCandidate candidate,
                                       String sourceRunId, String rootRunId) {
        if (candidate == null || !candidate.isComplete()
                || sourceRunId == null || sourceRunId.isBlank()) {
            return null;
        }
        EvidenceReceipt receipt = EvidenceReceipt.builder()
                .evidenceKey(candidate.getEvidenceKey())
                .normalizedScope(candidate.getNormalizedScope())
                .stateDigest(candidate.getStateDigest())
                .complete(true)
                .sourceRunId(sourceRunId)
                .rootRunId(rootRunId == null || rootRunId.isBlank() ? sourceRunId : rootRunId)
                .revalidation(candidate.getRevalidation())
                .build();
        return receipt.isRevalidatable() ? receipt : null;
    }

    public boolean isComplete() {
        return Boolean.TRUE.equals(complete);
    }

    @JsonIgnore
    public boolean isRevalidatable() {
        if (!isComplete()) {
            return false;
        }
        if (evidenceKey == null || evidenceKey.isBlank()
                || normalizedScope == null || normalizedScope.isBlank()
                || stateDigest == null || stateDigest.isBlank()
                || revalidation == null) {
            return false;
        }
        return revalidation.isValid();
    }
}
