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
    private String observationType;
    private String toolSemantics;
    private String normalizedScope;
    private String repositoryRelativePath;
    private Integer observedStartLine;
    private Integer observedEndLine;
    private String normalizedQuery;
    private String searchScope;
    private String engineVersion;
    private String digestAlgorithm;
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
                .observationType(candidate.getObservationType())
                .toolSemantics(candidate.getToolSemantics())
                .normalizedScope(candidate.getNormalizedScope())
                .repositoryRelativePath(candidate.getRepositoryRelativePath())
                .observedStartLine(candidate.getObservedStartLine())
                .observedEndLine(candidate.getObservedEndLine())
                .normalizedQuery(candidate.getNormalizedQuery())
                .searchScope(candidate.getSearchScope())
                .engineVersion(candidate.getEngineVersion())
                .digestAlgorithm(candidate.getDigestAlgorithm())
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
                || toolSemantics == null || toolSemantics.isBlank()
                || normalizedScope == null || normalizedScope.isBlank()
                || repositoryRelativePath == null || repositoryRelativePath.isBlank()
                || !"SHA-256".equals(digestAlgorithm)
                || stateDigest == null || stateDigest.isBlank()
                || revalidation == null
                || observationType == null || observationType.isBlank()) {
            return false;
        }
        if ("read_file".equals(observationType)) {
            if (observedStartLine == null || observedStartLine < 1
                    || observedEndLine == null || observedEndLine < observedStartLine) {
                return false;
            }
        } else if ("list_files".equals(observationType)) {
            if (observedStartLine != null || observedEndLine != null) {
                return false;
            }
        } else if ("search".equals(observationType)) {
            if (observedStartLine != null || observedEndLine != null
                    || normalizedQuery == null || normalizedQuery.isBlank()
                    || searchScope == null || searchScope.isBlank()
                    || engineVersion == null || engineVersion.isBlank()) {
                return false;
            }
        } else {
            return false;
        }
        return revalidation.matches(observationType, toolSemantics, repositoryRelativePath,
                observedStartLine, observedEndLine, normalizedQuery, searchScope, engineVersion);
    }
}
