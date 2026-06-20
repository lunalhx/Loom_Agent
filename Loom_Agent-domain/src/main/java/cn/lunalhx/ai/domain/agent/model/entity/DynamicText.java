package cn.lunalhx.ai.domain.agent.model.entity;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class DynamicText {

    private final List<DynamicTextEntry> entries = new ArrayList<>();

    public void append(int step, String sourceNode, String title, String content) {
        if (StringUtils.isBlank(content)) {
            return;
        }
        entries.add(DynamicTextEntry.builder()
                .step(step)
                .sourceNode(sourceNode)
                .title(title)
                .content(content)
                .build());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public List<DynamicTextEntry> entries() {
        return List.copyOf(entries);
    }

    public String render() {
        return entries.stream()
                .map(this::renderEntry)
                .collect(Collectors.joining("\n\n"));
    }

    private String renderEntry(DynamicTextEntry entry) {
        StringBuilder text = new StringBuilder();
        text.append("## Step ").append(entry.getStep()).append(" - ").append(entry.getTitle()).append('\n');
        text.append("SourceNode: ").append(entry.getSourceNode()).append('\n');
        text.append(entry.getContent());
        return text.toString();
    }

}
