package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.DynamicTextEntry;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.DynamicTextRole;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

class ContextSummaryComposer {

    private final AgentRuntimeProperties properties;

    ContextSummaryComposer(AgentRuntimeProperties properties) {
        this.properties = properties;
    }

    String compose(AgentContext context,
                   List<DynamicTextEntry> entries,
                   ContextArtifact transcriptArtifact,
                   int maxChars) {
        StringBuilder text = new StringBuilder();
        text.append("UserGoal: ").append(StringUtils.abbreviate(StringUtils.defaultString(context.getQuestion()), 800)).append('\n');
        if (context.getPlan() != null) {
            text.append("CurrentPlan:\n").append(StringUtils.abbreviate(context.getPlan().render(),
                    Math.max(256, maxChars / 2))).append('\n');
        }
        text.append("CompactedTranscriptArtifactId: ").append(transcriptArtifact.getArtifactId()).append('\n');
        text.append("DynamicEntriesBeforeSummary: ").append(entries.size()).append('\n');
        text.append("RecentTools:");
        List<DynamicTextEntry> recentTools = entries.stream()
                .filter(entry -> entry.getRole() == DynamicTextRole.ASSISTANT_ACTION || entry.getRole() == DynamicTextRole.TOOL_RESULT)
                .toList();
        recentTools.stream()
                .skip(Math.max(0, recentTools.size() - 12L))
                .forEach(entry -> text.append("\n- step=").append(entry.getStep())
                        .append(" role=").append(entry.getRole().code())
                        .append(" tool=").append(StringUtils.defaultString(entry.getTool(), "n/a"))
                        .append(StringUtils.isBlank(entry.getArtifactId()) ? "" : " artifactId=" + entry.getArtifactId()));
        text.append("\nNeed exact older context: call context_recall with list/search/get for artifactId values.");
        return StringUtils.abbreviate(text.toString(), Math.max(256, maxChars));
    }
}
