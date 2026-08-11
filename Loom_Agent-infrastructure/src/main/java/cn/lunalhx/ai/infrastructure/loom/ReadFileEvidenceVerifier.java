package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt;
import cn.lunalhx.ai.domain.tool.model.EvidenceObservationType;
import cn.lunalhx.ai.domain.tool.model.EvidenceRevalidation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Trusted revalidator for the v2 {@code read_file} evidence semantics. */
public final class ReadFileEvidenceVerifier {

    private ReadFileEvidenceVerifier() {
    }

    public static boolean matches(Path workspaceRoot, EvidenceReceipt receipt) {
        EvidenceRevalidation rule = receipt == null ? null : receipt.getRevalidation();
        if (workspaceRoot == null || receipt == null || !receipt.isRevalidatable()
                || rule.getObservationType() != EvidenceObservationType.READ_FILE
                || !"read_file:utf8-lines:v2".equals(rule.getToolSemantics())) {
            return false;
        }
        try {
            Path root = workspaceRoot.toRealPath();
            Path file = root.resolve(rule.getRepositoryRelativePath())
                    .normalize().toRealPath();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                return false;
            }
            String digest = ReadFileEvidenceSupport.digest(file,
                    rule.getStartLine(), rule.getEndLine());
            return receipt.getStateDigest().equals(digest);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
