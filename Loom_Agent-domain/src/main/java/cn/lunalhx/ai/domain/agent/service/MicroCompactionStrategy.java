package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.DynamicTextEntry;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.DynamicTextRole;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

class MicroCompactionStrategy implements ContextCompactionStrategy {

    private final AgentRuntimeProperties properties;

    MicroCompactionStrategy(AgentRuntimeProperties properties) {
        this.properties = properties;
    }

    @Override
    public ContextStrategyResult compact(AgentContext context, ContextCompactionCommand command) {
        List<DynamicTextEntry> entries = context.getDynamicText().entries();
        int keepRecent = positive(contextProperties().getKeepRecentToolResults(), 4);
        int seenToolResults = 0;
        boolean changed = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            DynamicTextEntry entry = entries.get(i);
            if (entry.getRole() != DynamicTextRole.TOOL_RESULT) {
                continue;
            }
            seenToolResults++;
            if (seenToolResults <= keepRecent || Boolean.TRUE.equals(entry.getCompacted())) {
                continue;
            }
            if (StringUtils.isBlank(entry.getArtifactId())) {
                continue;
            }
            String content = "[compacted_tool_result]\n"
                    + "artifactId=" + entry.getArtifactId() + "\n"
                    + "originalChars=" + nullToZero(entry.getOriginalChars()) + "\n"
                    + "Use context_recall get with this artifactId when exact output is needed.";
            entry.setSummary(content);
            entry.setContent(content);
            entry.setRenderChars(content.length());
            entry.setCompacted(true);
            changed = true;
        }
        if (!changed) {
            return ContextStrategyResult.unchanged();
        }
        return new ContextStrategyResult(true, "micro", entries.size(), null);
    }

    private int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private AgentRuntimeProperties.ContextProperties contextProperties() {
        if (properties.getContext() == null) {
            properties.setContext(new AgentRuntimeProperties.ContextProperties());
        }
        return properties.getContext();
    }
}
