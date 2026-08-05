package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.service.context.PreparedContextView;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatMessage;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;
import cn.lunalhx.ai.domain.model.valobj.ModelCapabilities;
import cn.lunalhx.ai.domain.model.valobj.OutputFormat;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a {@link PreparedContextView} into a {@link ChatPrompt}.
 *
 * <p>The view is produced once per round by {@code ContextReductionMiddleware}
 * and shared so budget estimation and the actual request stay in sync.
 */
public class ModelPromptFactory {

    ChatPrompt build(AgentContext context, String requestedModel, int requestedMaxTokens,
                     long deadlineEpochMs, PreparedContextView view) {
        requireLedgerReady(context);
        String systemPrompt = view.systemPrefix();

        List<ChatMessage> messages = new ArrayList<>(view.messages());

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
                .outputFormat(OutputFormat.TEXT)
                .runtimeProperties(context.getRunConfig() == null ? null : context.getRunConfig().model())
                .build();
    }

    public String budgetInput(PreparedContextView view) {
        return view.budgetText();
    }

    private void requireLedgerReady(AgentContext context) {
        if (!context.isLedgerReady()
                || context.getStablePrefix() == null
                || context.getConversationHistory() == null) {
            throw new IllegalStateException("conversation history is not ready");
        }
    }
}
