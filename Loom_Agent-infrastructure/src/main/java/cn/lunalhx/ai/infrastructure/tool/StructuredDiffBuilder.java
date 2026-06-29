package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.tool.model.ApprovalDiff;
import cn.lunalhx.ai.domain.tool.model.DiffHunk;
import cn.lunalhx.ai.domain.tool.model.DiffLine;
import cn.lunalhx.ai.domain.tool.model.DiffStats;
import cn.lunalhx.ai.domain.tool.model.InlineDiffPart;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class StructuredDiffBuilder {

    private static final int CONTEXT_RADIUS = 3;
    private static final double INLINE_SIMILARITY_THRESHOLD = 0.5D;
    static final long MAX_LCS_CELLS = 2_000_000L;

    private StructuredDiffBuilder() {
    }

    /**
     * Build a diff, or return null with a specific error code when the diff is too large.
     * Callers check {@code ApprovalDiff.getErrorCode()} for "diff_too_large".
     */
    static ApprovalDiff oldNew(String path, String oldText, String newText) {
        List<String> oldLines = splitLines(oldText);
        List<String> newLines = splitLines(newText);

        // Strip common prefix and suffix to narrow the LCS region
        int prefixLen = commonPrefix(oldLines, newLines);
        int suffixLen = commonSuffix(oldLines, newLines, prefixLen);

        List<String> oldMid = oldLines.subList(prefixLen, oldLines.size() - suffixLen);
        List<String> newMid = newLines.subList(prefixLen, newLines.size() - suffixLen);

        long cells = (long) oldMid.size() * (long) newMid.size();
        if (cells > MAX_LCS_CELLS) {
            return ApprovalDiff.builder()
                    .format("OLD_NEW")
                    .path(path)
                    .oldText(oldText)
                    .newText(newText)
                    .editable(false)
                    .errorCode("diff_too_large")
                    .build();
        }

        List<DiffLine> midDiff = lineDiff(oldMid, newMid, prefixLen);
        List<DiffLine> fullLines = new ArrayList<>();

        // Emit prefix as context (trimmed by clipContext later if too long)
        for (int k = 0; k < prefixLen; k++) {
            fullLines.add(line("context", k + 1, k + 1, oldLines.get(k)));
        }
        fullLines.addAll(midDiff);
        int oldSuffixStart = oldLines.size() - suffixLen;
        int newSuffixStart = newLines.size() - suffixLen;
        for (int k = 0; k < suffixLen; k++) {
            fullLines.add(line("context", oldSuffixStart + k + 1, newSuffixStart + k + 1, oldLines.get(oldSuffixStart + k)));
        }

        DiffStats stats = attachInlineDiff(fullLines);
        List<DiffLine> clippedLines = clipContext(fullLines);
        return ApprovalDiff.builder()
                .format("OLD_NEW")
                .path(path)
                .oldText(oldText)
                .newText(newText)
                .editable(false)
                .hunks(List.of(toHunk(clippedLines)))
                .stats(stats)
                .build();
    }

    private static int commonPrefix(List<String> a, List<String> b) {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            if (!a.get(i).equals(b.get(i))) {
                return i;
            }
        }
        return n;
    }

    private static int commonSuffix(List<String> a, List<String> b, int prefixLen) {
        int n = 0;
        int maxN = Math.min(a.size(), b.size()) - prefixLen;
        while (n < maxN && a.get(a.size() - 1 - n).equals(b.get(b.size() - 1 - n))) {
            n++;
        }
        return n;
    }

    private static List<String> splitLines(String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.isEmpty()) {
            return List.of();
        }
        String[] parts = normalized.split("\n", -1);
        if (normalized.endsWith("\n")) {
            return Arrays.asList(Arrays.copyOf(parts, parts.length - 1));
        }
        return Arrays.asList(parts);
    }

    private static List<DiffLine> lineDiff(List<String> oldLines, List<String> newLines, int lineOffset) {
        int m = oldLines.size();
        int n = newLines.size();
        int[][] lcs = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                if (oldLines.get(i).equals(newLines.get(j))) {
                    lcs[i][j] = lcs[i + 1][j + 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }

        List<DiffLine> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < m && j < n) {
            if (oldLines.get(i).equals(newLines.get(j))) {
                result.add(line("context", lineOffset + i + 1, lineOffset + j + 1, oldLines.get(i)));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                result.add(line("removed", lineOffset + i + 1, null, oldLines.get(i)));
                i++;
            } else {
                result.add(line("added", null, lineOffset + j + 1, newLines.get(j)));
                j++;
            }
        }
        while (i < m) {
            result.add(line("removed", lineOffset + i + 1, null, oldLines.get(i)));
            i++;
        }
        while (j < n) {
            result.add(line("added", null, lineOffset + j + 1, newLines.get(j)));
            j++;
        }
        return result;
    }

    private static DiffLine line(String type, Integer oldLineNumber, Integer newLineNumber, String text) {
        return DiffLine.builder()
                .type(type)
                .oldLineNumber(oldLineNumber)
                .newLineNumber(newLineNumber)
                .text(text)
                .build();
    }

    private static DiffStats attachInlineDiff(List<DiffLine> lines) {
        int added = (int) lines.stream().filter(line -> "added".equals(line.getType())).count();
        int removed = (int) lines.stream().filter(line -> "removed".equals(line.getType())).count();
        int modified = 0;
        int pairId = 1;
        int index = 0;
        while (index < lines.size()) {
            DiffLine line = lines.get(index);
            if ("context".equals(line.getType())) {
                index++;
                continue;
            }

            int start = index;
            while (index < lines.size() && !"context".equals(lines.get(index).getType())) {
                index++;
            }
            List<DiffLine> removedBlock = lines.subList(start, index).stream()
                    .filter(candidate -> "removed".equals(candidate.getType()))
                    .toList();
            List<DiffLine> addedBlock = lines.subList(start, index).stream()
                    .filter(candidate -> "added".equals(candidate.getType()))
                    .toList();
            int pairs = Math.min(removedBlock.size(), addedBlock.size());
            for (int offset = 0; offset < pairs; offset++) {
                DiffLine removedLine = removedBlock.get(offset);
                DiffLine addedLine = addedBlock.get(offset);
                if (similarity(removedLine.getText(), addedLine.getText()) >= INLINE_SIMILARITY_THRESHOLD) {
                    List<InlineDiffPart> inline = inlineDiff(removedLine.getText(), addedLine.getText());
                    removedLine.setPairId(pairId);
                    removedLine.setInlineDiff(inline);
                    addedLine.setPairId(pairId);
                    addedLine.setInlineDiff(inline);
                    pairId++;
                    modified++;
                }
            }
        }
        return DiffStats.builder()
                .added(added)
                .removed(removed)
                .modified(modified)
                .build();
    }

    private static double similarity(String oldText, String newText) {
        int maxLength = Math.max(oldText.length(), newText.length());
        if (maxLength == 0) {
            return 1D;
        }
        return 1D - ((double) levenshtein(oldText, newText) / (double) maxLength);
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private static List<InlineDiffPart> inlineDiff(String oldText, String newText) {
        int[][] lcs = new int[oldText.length() + 1][newText.length() + 1];
        for (int i = oldText.length() - 1; i >= 0; i--) {
            for (int j = newText.length() - 1; j >= 0; j--) {
                if (oldText.charAt(i) == newText.charAt(j)) {
                    lcs[i][j] = lcs[i + 1][j + 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }

        List<InlineDiffPart> parts = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < oldText.length() && j < newText.length()) {
            if (oldText.charAt(i) == newText.charAt(j)) {
                appendPart(parts, "unchanged", oldText.substring(i, i + 1));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                appendPart(parts, "removed", oldText.substring(i, i + 1));
                i++;
            } else {
                appendPart(parts, "added", newText.substring(j, j + 1));
                j++;
            }
        }
        while (i < oldText.length()) {
            appendPart(parts, "removed", oldText.substring(i, i + 1));
            i++;
        }
        while (j < newText.length()) {
            appendPart(parts, "added", newText.substring(j, j + 1));
            j++;
        }
        return parts;
    }

    private static void appendPart(List<InlineDiffPart> parts, String type, String text) {
        if (text.isEmpty()) {
            return;
        }
        if (!parts.isEmpty() && type.equals(parts.get(parts.size() - 1).getType())) {
            InlineDiffPart previous = parts.get(parts.size() - 1);
            previous.setText(previous.getText() + text);
            return;
        }
        parts.add(InlineDiffPart.builder().type(type).text(text).build());
    }

    private static List<DiffLine> clipContext(List<DiffLine> fullLines) {
        if (fullLines.stream().noneMatch(line -> !"context".equals(line.getType()))) {
            return fullLines;
        }
        boolean[] keep = new boolean[fullLines.size()];
        for (int i = 0; i < fullLines.size(); i++) {
            if (!"context".equals(fullLines.get(i).getType())) {
                int start = Math.max(0, i - CONTEXT_RADIUS);
                int end = Math.min(fullLines.size() - 1, i + CONTEXT_RADIUS);
                for (int j = start; j <= end; j++) {
                    keep[j] = true;
                }
            }
        }

        List<DiffLine> result = new ArrayList<>();
        int index = 0;
        while (index < fullLines.size()) {
            if (keep[index]) {
                result.add(fullLines.get(index));
                index++;
                continue;
            }
            int folded = 0;
            while (index < fullLines.size() && !keep[index]) {
                folded++;
                index++;
            }
            if (folded > 0) {
                result.add(DiffLine.builder()
                        .type("folded")
                        .foldedCount(folded)
                        .text("")
                        .build());
            }
        }
        return result;
    }

    private static DiffHunk toHunk(List<DiffLine> lines) {
        int oldStart = firstLineNumber(lines, true);
        int newStart = firstLineNumber(lines, false);
        return DiffHunk.builder()
                .oldStart(oldStart)
                .oldLines(countLines(lines, true))
                .newStart(newStart)
                .newLines(countLines(lines, false))
                .lines(lines)
                .build();
    }

    private static int firstLineNumber(List<DiffLine> lines, boolean oldSide) {
        return lines.stream()
                .map(line -> oldSide ? line.getOldLineNumber() : line.getNewLineNumber())
                .filter(number -> number != null && number > 0)
                .findFirst()
                .orElse(1);
    }

    private static int countLines(List<DiffLine> lines, boolean oldSide) {
        return (int) lines.stream()
                .filter(line -> oldSide ? line.getOldLineNumber() != null : line.getNewLineNumber() != null)
                .count();
    }

}
