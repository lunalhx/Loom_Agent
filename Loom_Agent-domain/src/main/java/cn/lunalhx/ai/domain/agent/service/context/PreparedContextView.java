package cn.lunalhx.ai.domain.agent.service.context;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatMessage;

import java.util.List;

/**
 * A prepared, immutable context view shared across the model call chain for a
 * single round. Built once by {@code ContextReductionMiddleware} and reused by
 * {@code BudgetMiddleware}, {@code ModelPromptFactory.build()} and
 * {@code budgetInput()} so budget estimation matches the actual request.
 */
public final class PreparedContextView {

    private final String systemPrefix;
    private final List<ChatMessage> messages;
    private final ContextBuildResult.ContextRenderMetadata metadata;

    PreparedContextView(String systemPrefix, List<ChatMessage> messages,
                        ContextBuildResult.ContextRenderMetadata metadata) {
        this.systemPrefix = systemPrefix;
        this.messages = List.copyOf(messages);
        this.metadata = metadata;
    }

    public static PreparedContextView from(ContextBuildResult result) {
        return new PreparedContextView(result.systemPrefix(), result.messages(), result.metadata());
    }

    public String systemPrefix() { return systemPrefix; }
    public List<ChatMessage> messages() { return messages; }
    public ContextBuildResult.ContextRenderMetadata metadata() { return metadata; }

    public String budgetText() {
        StringBuilder sb = new StringBuilder();
        if (systemPrefix != null) {
            sb.append(systemPrefix);
        }
        for (ChatMessage m : messages) {
            sb.append(m.getContent());
        }
        return sb.toString();
    }
}
