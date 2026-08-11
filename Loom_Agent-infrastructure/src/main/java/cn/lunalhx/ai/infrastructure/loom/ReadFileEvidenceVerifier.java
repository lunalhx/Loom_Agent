package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Trusted revalidator for the v1 {@code read_file} evidence semantics. */
public final class ReadFileEvidenceVerifier {

    private ReadFileEvidenceVerifier() {
    }

    public static boolean matches(Path workspaceRoot, EvidenceReceipt receipt) {
        if (workspaceRoot == null || receipt == null || !receipt.isRevalidatable()
                || !"read_file:utf8-lines:v1".equals(receipt.getToolSemantics())) {
            return false;
        }
        try {
            Path root = workspaceRoot.toRealPath();
            Path file = root.resolve(receipt.getRepositoryRelativePath())
                    .normalize().toRealPath();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                return false;
            }
            String digest = ReadFileEvidenceSupport.digest(file,
                    receipt.getObservedStartLine(), receipt.getObservedEndLine());
            return receipt.getRevalidation().matches("read_file", receipt.getToolSemantics(),
                    receipt.getRepositoryRelativePath(), receipt.getObservedStartLine(),
                    receipt.getObservedEndLine(), receipt.getNormalizedQuery(),
                    receipt.getSearchScope(), receipt.getEngineVersion())
                    && receipt.getStateDigest().equals(digest);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
