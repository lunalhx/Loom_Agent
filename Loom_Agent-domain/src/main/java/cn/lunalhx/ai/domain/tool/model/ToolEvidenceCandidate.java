package cn.lunalhx.ai.domain.tool.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Transient safe metadata emitted by a trusted tool adapter. It deliberately
 * contains a digest and scope, never the observed content.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolEvidenceCandidate {

    private String evidenceKey;
    private String normalizedScope;
    private String stateDigest;
    private Boolean complete;
    private EvidenceRevalidation revalidation;

    @JsonIgnore
    public boolean isComplete() {
        return Boolean.TRUE.equals(complete)
                && evidenceKey != null && !evidenceKey.isBlank()
                && normalizedScope != null && !normalizedScope.isBlank()
                && stateDigest != null && !stateDigest.isBlank()
                && revalidation != null && revalidation.isValid();
    }
}
