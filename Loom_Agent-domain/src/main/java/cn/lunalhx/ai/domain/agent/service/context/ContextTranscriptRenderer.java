package cn.lunalhx.ai.domain.agent.service.context;

import cn.lunalhx.ai.domain.agent.model.entity.DynamicTextEntry;
import cn.lunalhx.ai.domain.agent.model.valobj.DynamicTextRole;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

class ContextTranscriptRenderer {

    String renderEntries(List<DynamicTextEntry> entries) {
        return entries.stream().map(this::renderEntry).collect(Collectors.joining("\n\n"));
    }

    String renderEntry(DynamicTextEntry entry) {
        StringBuilder text = new StringBuilder("## ");
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
        text.append(StringUtils.defaultString(entry.getContent()));
        return text.toString();
    }

    List<String> transcriptEntries(String transcript) {
        if (StringUtils.isBlank(transcript)) {
            return List.of();
        }
        return Arrays.stream(transcript.split("\\n\\n(?=## )"))
                .filter(StringUtils::isNotBlank)
                .toList();
    }

    DynamicTextEntry systemNote(String title, String content) {
        return DynamicTextEntry.builder()
                .entryId(UUID.randomUUID().toString())
                .step(0)
                .role(DynamicTextRole.SYSTEM_NOTE)
                .sourceNode("context_window_manager")
                .title(title)
                .content(content)
                .originalChars(StringUtils.length(content))
                .renderChars(StringUtils.length(content))
                .compacted(true)
                .summary(content)
                .build();
    }
}
