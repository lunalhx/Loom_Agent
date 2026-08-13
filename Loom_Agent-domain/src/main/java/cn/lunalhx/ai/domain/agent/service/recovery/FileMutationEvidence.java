package cn.lunalhx.ai.domain.agent.service.recovery;

import cn.lunalhx.ai.domain.agent.model.entity.ToolExecutionMarker;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.service.FileMutationTools;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Adapter-specific write_file/patch_file recovery evidence: a workspace-relative
 * safety target and SHA-256 of intended file bytes. Never persists raw content.
 */
public final class FileMutationEvidence {

    private FileMutationEvidence() {
    }

    public static void capture(ToolExecutionMarker marker, ToolCall call, Path workspace) {
        if (marker == null || call == null || !FileMutationTools.isFileMutation(call.getName())) {
            return;
        }
        safetyTarget(call, workspace).ifPresent(marker::setSafetyTarget);
        expectedDigest(call, workspace).ifPresent(marker::setExpectedDigest);
    }

    public static boolean matchesCurrentState(ToolExecutionMarker marker, Path workspace) {
        if (marker == null || workspace == null
                || StringUtils.isBlank(marker.getSafetyTarget())
                || StringUtils.isBlank(marker.getExpectedDigest())) {
            return false;
        }
        Path file = resolveInsideWorkspace(workspace, marker.getSafetyTarget()).orElse(null);
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        try {
            return marker.getExpectedDigest().equals(DigestUtils.sha256Hex(Files.readAllBytes(file)));
        } catch (IOException e) {
            return false;
        }
    }

    private static Optional<String> safetyTarget(ToolCall call, Path workspace) {
        String rawPath = text(call, "path");
        if (StringUtils.isBlank(rawPath) || workspace == null) {
            return Optional.empty();
        }
        Path root = workspace.toAbsolutePath().normalize();
        return resolveInsideWorkspace(root, rawPath)
                .map(path -> root.relativize(path).toString().replace('\\', '/'));
    }

    private static Optional<String> expectedDigest(ToolCall call, Path workspace) {
        if ("write_file".equals(call.getName())) {
            String content = text(call, "content");
            if (content == null) {
                return Optional.empty();
            }
            return Optional.of(DigestUtils.sha256Hex(content.getBytes(StandardCharsets.UTF_8)));
        }
        if ("patch_file".equals(call.getName())) {
            return patchDigest(call, workspace);
        }
        return Optional.empty();
    }

    private static Optional<String> patchDigest(ToolCall call, Path workspace) {
        String oldText = text(call, "old_text");
        String newText = text(call, "new_text");
        if (oldText == null || oldText.isEmpty() || newText == null || workspace == null) {
            return Optional.empty();
        }
        Path file = resolveInsideWorkspace(workspace, text(call, "path")).orElse(null);
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (countOccurrences(content, oldText) != 1) {
                return Optional.empty();
            }
            String expected = content.replace(oldText, newText);
            return Optional.of(DigestUtils.sha256Hex(expected.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<Path> resolveInsideWorkspace(Path workspace, String rawPath) {
        if (workspace == null || StringUtils.isBlank(rawPath)) {
            return Optional.empty();
        }
        Path root = workspace.toAbsolutePath().normalize();
        Path raw = Path.of(rawPath);
        Path candidate = (raw.isAbsolute() ? raw : root.resolve(raw)).normalize().toAbsolutePath();
        if (!candidate.startsWith(root)) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String text(ToolCall call, String key) {
        if (call.getInput() == null || !call.getInput().has(key) || call.getInput().path(key).isNull()) {
            return null;
        }
        return call.getInput().path(key).asText(null);
    }
}
