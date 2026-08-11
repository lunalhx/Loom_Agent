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
    private String toolSemantics;
    private String repositoryRelativePath;
    private Integer startLine;
    private Integer endLine;

    public boolean matches(String semantics, String path, Integer start, Integer end) {
        return java.util.Objects.equals(toolSemantics, semantics)
                && java.util.Objects.equals(repositoryRelativePath, path)
                && java.util.Objects.equals(startLine, start)
                && java.util.Objects.equals(endLine, end)
                && "SHA-256".equals(digestAlgorithm);
    }
}
