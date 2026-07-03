package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedger;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedgerEntry;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatMessage;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;
import cn.lunalhx.ai.domain.model.valobj.ModelCapabilities;
import cn.lunalhx.ai.domain.model.valobj.OutputFormat;

import java.util.ArrayList;
import java.util.List;

final class ModelPromptFactory {

    ChatPrompt build(AgentContext context, String requestedModel, int requestedMaxTokens, long deadlineEpochMs) {
        requireLedgerReady(context);
        return buildLedgerPrompt(context, requestedModel, requestedMaxTokens, deadlineEpochMs);
    }

    String budgetInput(AgentContext context) {
        requireLedgerReady(context);
        return buildLedgerBudgetInput(context);
    }

    // ================================================================
    // Ledger-only prompt construction
    // ================================================================

    private ChatPrompt buildLedgerPrompt(AgentContext context, String requestedModel,
                                          int requestedMaxTokens, long deadlineEpochMs) {
        StablePrefix stablePrefix = context.getStablePrefix();
        String systemPrompt = stablePrefix != null ? stablePrefix.frozenContent() : "";

        List<ChatMessage> messages = new ArrayList<>();
        ConversationLedger ledger = context.getConversationLedger();
        if (ledger != null) {
            for (ConversationLedgerEntry entry : ledger.entries()) {
                messages.add(ChatMessage.builder()
                        .role(entry.role())
                        .content(entry.content())
                        .build());
            }
        }

        return ChatPrompt.builder()
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .systemPrompt(systemPrompt)
                .message(null)
                .messages(messages)
                .model(requestedModel)
                .maxTokens(requestedMaxTokens <= 0 ? null : requestedMaxTokens)
                .capability(ModelCapabilities.COMPLETE_AGENT_DECISION)
                .purpose(ModelCallPurpose.CONTROL_JSON)
                .deadlineEpochMs(deadlineEpochMs)
                .outputFormat(OutputFormat.JSON_OBJECT)
                .build();
    }

    private String buildLedgerBudgetInput(AgentContext context) {
        StringBuilder sb = new StringBuilder();
        StablePrefix stablePrefix = context.getStablePrefix();
        if (stablePrefix != null && stablePrefix.frozenContent() != null) {
            sb.append(stablePrefix.frozenContent());
        }
        ConversationLedger ledger = context.getConversationLedger();
        if (ledger != null) {
            for (ConversationLedgerEntry entry : ledger.entries()) {
                sb.append(entry.content());
            }
        }
        return sb.toString();
    }

    private void requireLedgerReady(AgentContext context) {
        if (!context.isLedgerReady()
                || context.getStablePrefix() == null
                || context.getConversationLedger() == null) {
            throw new IllegalStateException("conversation ledger is not ready");
        }
    }
}
