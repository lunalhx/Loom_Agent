package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import org.apache.commons.lang3.StringUtils;

class ContextTokenEstimator {

    private final AgentRuntimeProperties properties;

    ContextTokenEstimator(AgentRuntimeProperties properties) {
        this.properties = properties;
    }

    int estimateTokens(AgentContext context) {
        if (context == null) {
            return 0;
        }
        int chars = StringUtils.length(context.getQuestion())
                + StringUtils.length(context.getDynamicText() == null ? "" : context.getDynamicText().render())
                + (context.getPlan() == null ? 0 : StringUtils.length(context.getPlan().render()))
                + StringUtils.length(context.getSkillCatalogText())
                + context.getToolSpecs().stream().mapToInt(spec -> StringUtils.length(spec.getName())
                + StringUtils.length(spec.getDescription()) + StringUtils.length(spec.getInputSchema())).sum();
        int charsPerToken = positive(properties.getBudget().getEstimatedCharsPerToken(), 4);
        return Math.max(1, chars / charsPerToken);
    }

    int summaryCharsForTarget(int targetTokens) {
        int configured = positive(contextProperties().getSummaryMaxChars(), 6000);
        int targetChars = Math.max(1024,
                targetTokens * positive(properties.getBudget().getEstimatedCharsPerToken(), 4) / 3);
        return Math.min(configured, targetChars);
    }

    boolean enabled() {
        return Boolean.TRUE.equals(contextProperties().getEnabled());
    }

    AgentRuntimeProperties.ContextProperties contextProperties() {
        if (properties.getContext() == null) {
            properties.setContext(new AgentRuntimeProperties.ContextProperties());
        }
        return properties.getContext();
    }

    int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}
