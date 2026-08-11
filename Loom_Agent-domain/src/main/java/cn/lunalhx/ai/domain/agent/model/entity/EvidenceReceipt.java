package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.tool.model.EvidenceRevalidation;
import cn.lunalhx.ai.domain.tool.model.ToolEvidenceCandidate;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
    private String toolSemantics;
    private String normalizedScope;
    private String repositoryRelativePath;
    private Integer observedStartLine;
    private Integer observedEndLine;
    private String digestAlgorithm;
    private String stateDigest;
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
                .toolSemantics(candidate.getToolSemantics())
                .normalizedScope(candidate.getNormalizedScope())
                .repositoryRelativePath(candidate.getRepositoryRelativePath())
                .observedStartLine(candidate.getObservedStartLine())
                .observedEndLine(candidate.getObservedEndLine())
                .digestAlgorithm(candidate.getDigestAlgorithm())
                .stateDigest(candidate.getStateDigest())
                .complete(true)
                .sourceRunId(sourceRunId)
                .rootRunId(rootRunId == null || rootRunId.isBlank() ? sourceRunId : rootRunId)
                .revalidation(candidate.getRevalidation())
                .build();
        return receipt.isRevalidatable() ? receipt : null;
    }

    @JsonIgnore
    public boolean isComplete() {
        return Boolean.TRUE.equals(complete);
    }

    @JsonIgnore
    public boolean isRevalidatable() {
        return isComplete()
                && evidenceKey != null && !evidenceKey.isBlank()
                && toolSemantics != null && !toolSemantics.isBlank()
                && normalizedScope != null && !normalizedScope.isBlank()
                && repositoryRelativePath != null && !repositoryRelativePath.isBlank()
                && observedStartLine != null && observedStartLine >= 1
                && observedEndLine != null && observedEndLine >= observedStartLine
                && "SHA-256".equals(digestAlgorithm)
                && stateDigest != null && !stateDigest.isBlank()
                && revalidation != null
                && revalidation.matches(toolSemantics, repositoryRelativePath,
                observedStartLine, observedEndLine);
    }
}
