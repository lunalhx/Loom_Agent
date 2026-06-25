package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.DynamicTextEntry;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.DynamicTextRole;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

class ContextSummaryRewriter {

    private final AgentRuntimeProperties properties;
    private final ContextArtifactService artifactService;
    private final ContextTranscriptRenderer renderer;

    ContextSummaryRewriter(AgentRuntimeProperties properties,
                           ContextArtifactService artifactService,
                           ContextTranscriptRenderer renderer) {
        this.properties = properties;
        this.artifactService = artifactService;
        this.renderer = renderer;
    }

    int replaceWithSummary(AgentContext context,
                           List<DynamicTextEntry> entries,
                           String summary,
                           String title,
                           int keepRecent,
                           int targetTokens) {
        List<DynamicTextEntry> next = new ArrayList<>();
        entries.stream()
                .filter(entry -> entry.getRole() == DynamicTextRole.USER_TASK)
                .findFirst()
                .ifPresent(next::add);
        next.add(renderer.systemNote(title, summary));

        List<DynamicTextEntry> candidates = entries.stream()
                .filter(entry -> entry.getRole() != DynamicTextRole.USER_TASK)
                .toList();
        int start = Math.max(0, candidates.size() - Math.max(0, keepRecent));
        List<DynamicTextEntry> recent = new ArrayList<>(candidates.subList(start, candidates.size()));
        int maxEntryChars = Math.max(512,
                targetTokens * positive(properties.getBudget().getEstimatedCharsPerToken(), 4)
                        / Math.max(1, keepRecent + 2));
        for (DynamicTextEntry entry : recent) {
            next.add(sizeEntry(context, entry, maxEntryChars));
        }
        context.getDynamicText().replaceEntries(next);
        return recent.size();
    }

    private DynamicTextEntry sizeEntry(AgentContext context, DynamicTextEntry entry, int maxChars) {
        String rendered = renderer.renderEntry(entry);
        if (rendered.length() <= maxChars) {
            return entry;
        }
        ContextArtifact artifact = artifactService.persistEntry(context, rendered,
                Math.min(positive(contextProperties().getToolPreviewChars(), 2000), Math.max(128, maxChars / 2)));
        String content = artifactService.renderArtifactReference(artifact);
        return DynamicTextEntry.builder()
                .entryId(entry.getEntryId())
                .step(entry.getStep())
                .role(entry.getRole())
                .sourceNode(entry.getSourceNode())
                .title(entry.getTitle())
                .tool(entry.getTool())
                .content(content)
                .artifactId(artifact.getArtifactId())
                .originalChars(artifact.getOriginalChars())
                .renderChars(content.length())
                .compacted(true)
                .summary(content)
                .build();
    }

    private int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private AgentRuntimeProperties.ContextProperties contextProperties() {
        if (properties.getContext() == null) {
            properties.setContext(new AgentRuntimeProperties.ContextProperties());
        }
        return properties.getContext();
    }
}
