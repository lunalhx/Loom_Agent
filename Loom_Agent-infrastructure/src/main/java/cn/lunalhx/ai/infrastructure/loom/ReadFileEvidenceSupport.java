package cn.lunalhx.ai.infrastructure.loom;

import org.apache.commons.codec.digest.DigestUtils;

import java.io.IOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Shared semantic read and digest rules for capture and revalidation. */
final class ReadFileEvidenceSupport {

    private static final java.nio.charset.CharsetDecoder UTF8_DECODER = StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);

    private ReadFileEvidenceSupport() {
    }

    static List<String> readLines(Path file) throws IOException {
        String content = UTF8_DECODER.decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file))).toString();
        String normalizedContent = content.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(Arrays.asList(normalizedContent.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    static String digest(Path file, int startLine, int endLine) throws IOException {
        List<String> lines = readLines(file);
        return digest(lines, startLine, endLine);
    }

    static String digest(List<String> lines, int startLine, int endLine) {
        if (startLine < 1 || endLine < startLine) {
            return null;
        }
        int lastLine = Math.min(endLine, lines.size());
        List<String> visibleLines = startLine <= lastLine
                ? lines.subList(startLine - 1, lastLine)
                : List.of();
        String canonicalObservation = visibleLines.size() + "\n"
                + String.join("\n", visibleLines);
        return DigestUtils.sha256Hex(canonicalObservation);
    }
}
