package cn.lunalhx.ai.domain.tool.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

/** Deterministic inputs required to re-check one semantic tool observation. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceRevalidation {

    private String digestAlgorithm;
    private EvidenceObservationType observationType;
    private String toolSemantics;
    private String repositoryRelativePath;
    private Integer startLine;
    private Integer endLine;
    private String normalizedQuery;
    private String searchScope;
    private String engineVersion;

    @JsonIgnore
    public boolean isValid() {
        if (!"SHA-256".equals(digestAlgorithm)
                || observationType == null
                || StringUtils.isAnyBlank(toolSemantics, repositoryRelativePath)) {
            return false;
        }
        return switch (observationType) {
            case READ_FILE -> startLine != null && startLine >= 1
                    && endLine != null && endLine >= startLine
                    && normalizedQuery == null && searchScope == null && engineVersion == null;
            case LIST_FILES -> startLine == null && endLine == null
                    && normalizedQuery == null && searchScope == null && engineVersion == null;
            case SEARCH -> startLine == null && endLine == null
                    && StringUtils.isNoneBlank(normalizedQuery, searchScope, engineVersion);
        };
    }
}
