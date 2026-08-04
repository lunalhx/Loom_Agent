package cn.lunalhx.ai.domain.agent.service.context;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory;
import cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory.MemoryNote;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolOperation;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Updates {@link WorkingContextMemory} after tool completion.
 *
 * <ul>
 *   <li>{@code read_file} → record file, extract first 3 non-empty lines as a
 *       summary (≤180 chars), write an episodic note.</li>
 *   <li>{@code write_file}/{@code replace_in_file}/{@code delete_files} → record
 *       related paths and invalidate stale summaries.</li>
 *   <li>Partial/failed/rejected results → write a short process note.</li>
 * </ul>
 */
public final class WorkingContextMemoryService {

    private static final int FILE_SUMMARY_CHARS = 180;
    private static final int MAX_SUMMARY_LINES = 3;
    private static final AtomicLong SEQUENCE = new AtomicLong(0);

    public void onToolResult(AgentContext context, String toolName, ToolResult result) {
        if (context == null || toolName == null) {
            return;
        }
        WorkingContextMemory wm = context.workingMemoryOrCreate();
        List<String> paths = pathsOf(context, toolName);
        boolean success = result != null && result.isSuccess();

        switch (toolName) {
            case "read_file" -> handleRead(context, wm, paths, result);
            case "write_file", "patch_file" -> handleWrite(wm, paths, success, toolName, result);
            case "run_shell" -> handleShell(wm, result, success);
            default -> handleGeneric(wm, toolName, success, result);
        }
    }

    private void handleRead(AgentContext context, WorkingContextMemory wm, List<String> paths, ToolResult result) {
        for (String path : paths) {
            wm.recordRecentFile(path);
            String summary = summarizeReadOutput(context, path, result);
            String sha = sha256Of(context, path);
            if (sha == null) {
                wm.invalidateFileSummary(path);
                continue;
            }
            wm.putFileSummary(new WorkingContextMemory.FileSummary(path, summary, Instant.now(), sha));
        }
        if (result != null && !result.isSuccess()) {
            wm.addNote(note("read_file 失败: " + errorText(result),
                    List.of("read_file", "error"), pathSource(paths)));
        } else if (!paths.isEmpty()) {
            wm.addNote(note("read_file: " + StringUtils.abbreviate(paths.get(0), 120),
                    List.of("read_file"), pathSource(paths)));
        }
    }

    private void handleWrite(WorkingContextMemory wm, List<String> paths, boolean success, String toolName, ToolResult result) {
        for (String path : paths) {
            wm.recordRecentFile(path);
            wm.invalidateFileSummary(path);
        }
        if (paths.isEmpty() && result != null && !result.isSuccess()) {
            wm.addNote(note(toolName + " 失败: " + errorText(result),
                    List.of(toolName, "error"), pathSource(paths)));
        } else if (!paths.isEmpty()) {
            wm.addNote(note(toolName + ": " + StringUtils.abbreviate(String.join(",", paths), 120),
                    List.of(toolName), pathSource(paths)));
        }
    }

    private void handleShell(WorkingContextMemory wm, ToolResult result, boolean success) {
        if (result == null) {
            return;
        }
        if (success) {
            wm.addNote(note("run_shell: " + StringUtils.abbreviate(
                    firstNonEmptyLines(result.getObservation(), 1), 120),
                    List.of("run_shell"), null));
        } else {
            wm.addNote(note("run_shell 失败: " + errorText(result),
                    List.of("run_shell", "error"), null));
        }
    }

    private void handleGeneric(WorkingContextMemory wm, String toolName, boolean success, ToolResult result) {
        if (!success && result != null) {
            wm.addNote(note(toolName + " 未成功: " + errorText(result),
                    List.of(toolName, "error"), null));
        }
    }

    private MemoryNote note(String text, List<String> tags, String source) {
        return MemoryNote.builder()
                .text(text)
                .tags(tags)
                .source(source)
                .createdAt(Instant.now())
                .sequence(SEQUENCE.incrementAndGet())
                .kind("process")
                .build();
    }

    private String pathSource(List<String> paths) {
        return paths == null || paths.isEmpty() ? null : paths.get(0);
    }

    private String summarizeReadOutput(AgentContext context, String path, ToolResult result) {
        if (result == null || StringUtils.isBlank(result.getObservation())) {
            return "(empty)";
        }
        // For large reads the observation is already an artifact reference with a preview.
        String body = unwrapToolBoundary(result.getObservation());
        String summary = StringUtils.abbreviate(firstNonEmptyLines(body, MAX_SUMMARY_LINES), FILE_SUMMARY_CHARS);
        return summary.isBlank() ? "(empty)" : summary;
    }

    private String sha256Of(AgentContext context, String path) {
        try {
            Path resolved = context.getResolvedWorkspace() == null
                    ? Path.of(path) : context.getResolvedWorkspace().resolve(path);
            if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
                return null;
            }
            return DigestUtils.sha256Hex(Files.readAllBytes(resolved));
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> pathsOf(AgentContext context, String toolName) {
        AgentDecision decision = context.getDecision();
        if (decision == null || decision.getInput() == null) {
            return List.of();
        }
        return ToolOperation.inputPaths(decision.getInput());
    }

    private String firstNonEmptyLines(String value, int maxLines) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int added = 0;
        for (String line : value.split("\n", -1)) {
            if (StringUtils.isBlank(line)) {
                continue;
            }
            if (added >= maxLines) {
                break;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
            added++;
        }
        return sb.toString();
    }

    private String unwrapToolBoundary(String content) {
        String open = "<untrusted_tool_output>\n";
        String close = "\n</untrusted_tool_output>";
        if (content != null && content.startsWith(open) && content.endsWith(close)) {
            return content.substring(open.length(), content.length() - close.length());
        }
        return content == null ? "" : content;
    }

    private String errorText(ToolResult result) {
        return StringUtils.defaultIfBlank(result.getMessage(),
                StringUtils.defaultIfBlank(result.getErrorCode(), "unknown error"));
    }
}
