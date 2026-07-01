package cn.lunalhx.ai.domain.agent.service.context;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.DynamicTextEntry;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.DynamicTextRole;

import java.util.ArrayList;
import java.util.List;

class SnipCompactionStrategy implements ContextCompactionStrategy {

    private final AgentRuntimeProperties properties;
    private final ContextTranscriptRenderer renderer;

    SnipCompactionStrategy(AgentRuntimeProperties properties, ContextTranscriptRenderer renderer) {
        this.properties = properties;
        this.renderer = renderer;
    }

    @Override
    public ContextStrategyResult compact(AgentContext context, ContextCompactionCommand command) {
        List<DynamicTextEntry> entries = new ArrayList<>(context.getDynamicText().entries());
        int maxEntries = positive(contextProperties().getMaxDynamicEntries(), 60);
        if (entries.size() <= maxEntries) {
            return ContextStrategyResult.unchanged();
        }
        int tailSize = Math.max(10, maxEntries - 2);
        List<DynamicTextEntry> next = new ArrayList<>();
        entries.stream()
                .filter(entry -> entry.getRole() == DynamicTextRole.USER_TASK)
                .findFirst()
                .ifPresent(next::add);
        int omitted = Math.max(0, entries.size() - tailSize - next.size());
        next.add(renderer.systemNote("Context Snip Compact",
                "Omitted " + omitted + " older dynamic context entries. "
                        + "Artifact-backed outputs remain recoverable through context_recall."));
        next.addAll(entries.subList(Math.max(0, entries.size() - tailSize), entries.size()));
        entries.clear();
        entries.addAll(next);
        context.getDynamicText().replaceEntries(entries);
        return new ContextStrategyResult(true, "snip", entries.size(), null);
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
