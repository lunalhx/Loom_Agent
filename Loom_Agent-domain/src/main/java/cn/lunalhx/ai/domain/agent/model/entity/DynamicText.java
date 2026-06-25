package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.DynamicTextRole;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class DynamicText {

    private final List<DynamicTextEntry> entries = new ArrayList<>();

    public void appendSystemNote(int step, String sourceNode, String title, String content) {
        append(step, DynamicTextRole.SYSTEM_NOTE, sourceNode, title, null, null, content);
    }

    public void appendUserTask(String question) {
        append(0, DynamicTextRole.USER_TASK, "start", "User Task", null, null, question);
    }

    public void appendUserInput(int step, String content) {
        append(step, DynamicTextRole.USER_INPUT, "user_input", "User Input", null, null, content);
    }

    public void appendAssistantAction(int step, String sourceNode, AgentDecision decision) {
        if (decision == null) {
            return;
        }
        append(step,
                DynamicTextRole.ASSISTANT_ACTION,
                sourceNode,
                "Assistant Action",
                decision.getTool(),
                String.valueOf(decision.getInputView()),
                "Thought: " + StringUtils.defaultString(decision.getThought()));
    }

    public void appendToolResult(int step, String sourceNode, AgentDecision decision, String content) {
        append(step,
                DynamicTextRole.TOOL_RESULT,
                sourceNode,
                "Tool Result",
                decision == null ? null : decision.getTool(),
                decision == null ? null : String.valueOf(decision.getInputView()),
                content);
    }

    public void appendToolResult(int step, String sourceNode, AgentDecision decision, ToolResult result, String content) {
        DynamicTextEntry entry = newEntry(step,
                DynamicTextRole.TOOL_RESULT,
                sourceNode,
                "Tool Result",
                decision == null ? null : decision.getTool(),
                decision == null ? null : String.valueOf(decision.getInputView()),
                content);
        if (result != null) {
            entry.setArtifactId(result.getArtifactId());
            entry.setOriginalChars(result.getOriginalChars());
            entry.setRenderChars(StringUtils.length(content));
        }
        entries.add(entry);
    }

    private void append(int step,
                        DynamicTextRole role,
                        String sourceNode,
                        String title,
                        String tool,
                        String input,
                        String content) {
        if (StringUtils.isBlank(content)) {
            return;
        }
        entries.add(newEntry(step, role, sourceNode, title, tool, input, content));
    }

    private DynamicTextEntry newEntry(int step,
                                      DynamicTextRole role,
                                      String sourceNode,
                                      String title,
                                      String tool,
                                      String input,
                                      String content) {
        return DynamicTextEntry.builder()
                .entryId(UUID.randomUUID().toString())
                .step(step)
                .role(role)
                .sourceNode(sourceNode)
                .title(title)
                .tool(tool)
                .input(input)
                .content(content)
                .originalChars(StringUtils.length(content))
                .renderChars(StringUtils.length(content))
                .compacted(false)
                .build();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public List<DynamicTextEntry> entries() {
        return List.copyOf(entries);
    }

    public void replaceEntries(List<DynamicTextEntry> newEntries) {
        entries.clear();
        if (newEntries != null) {
            entries.addAll(newEntries);
        }
    }

    public String render() {
        return entries.stream()
                .map(this::renderEntry)
                .collect(Collectors.joining("\n\n"));
    }

    private String renderEntry(DynamicTextEntry entry) {
        StringBuilder text = new StringBuilder();
        text.append("## ");
        if (entry.getStep() > 0) {
            text.append("Step ").append(entry.getStep()).append(" - ");
        }
        text.append(entry.getRole().code()).append(" - ").append(entry.getTitle()).append('\n');
        text.append("SourceNode: ").append(entry.getSourceNode()).append('\n');
        if (StringUtils.isNotBlank(entry.getTool())) {
            text.append("Tool: ").append(entry.getTool()).append('\n');
        }
        if (StringUtils.isNotBlank(entry.getInput())) {
            text.append("Input: ").append(entry.getInput()).append('\n');
        }
        text.append(entry.getContent());
        return text.toString();
    }

}
