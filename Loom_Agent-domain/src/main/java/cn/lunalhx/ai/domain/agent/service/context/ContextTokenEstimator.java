package cn.lunalhx.ai.domain.agent.service.context;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedger;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedgerEntry;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

class ContextTokenEstimator {

    private final AgentRuntimeProperties properties;

    ContextTokenEstimator(AgentRuntimeProperties properties) {
        this.properties = properties;
    }

    int estimateTokens(AgentContext context) {
        if (context == null) {
            return 0;
        }
        int ledgerChars = 0;
        ConversationLedger ledger = context.getConversationLedger();
        if (ledger != null) {
            List<ConversationLedgerEntry> entries = ledger.entries();
            for (ConversationLedgerEntry entry : entries) {
                ledgerChars += StringUtils.length(entry.content());
            }
        }
        int chars = StringUtils.length(context.getQuestion())
                + ledgerChars
                + (context.getPlan() == null ? 0 : StringUtils.length(context.getPlan().render()))
                + StringUtils.length(context.getSkillCatalogText())
                + context.getToolSpecs().stream().mapToInt(spec -> StringUtils.length(spec.getName())
                + StringUtils.length(spec.getDescription()) + StringUtils.length(spec.getInputSchema())).sum();
        AgentRuntimeProperties runProperties = context.runtimeProperties(properties);
        int charsPerToken = positive(runProperties.getBudget().getEstimatedCharsPerToken(), 4);
        return Math.max(1, chars / charsPerToken);
    }

    int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
