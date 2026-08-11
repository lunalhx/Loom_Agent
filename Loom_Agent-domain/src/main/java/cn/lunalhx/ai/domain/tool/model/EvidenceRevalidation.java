package cn.lunalhx.ai.domain.tool.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Deterministic inputs required to re-check one semantic tool observation. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceRevalidation {

    private String digestAlgorithm;
    private String observationType;
    private String toolSemantics;
    private String repositoryRelativePath;
    private Integer startLine;
    private Integer endLine;
    private String normalizedQuery;
    private String searchScope;
    private String engineVersion;

    public boolean matches(String type, String semantics, String path, Integer start, Integer end,
                           String query, String scope, String engine) {
        return java.util.Objects.equals(observationType, type)
                && java.util.Objects.equals(toolSemantics, semantics)
                && java.util.Objects.equals(repositoryRelativePath, path)
                && java.util.Objects.equals(startLine, start)
                && java.util.Objects.equals(endLine, end)
                && java.util.Objects.equals(normalizedQuery, query)
                && java.util.Objects.equals(searchScope, scope)
                && java.util.Objects.equals(engineVersion, engine)
                && "SHA-256".equals(digestAlgorithm);
    }
}
