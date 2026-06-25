package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.BudgetCheckResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.TraceCost;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelCapabilities;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import lombok.Builder;
import lombok.Value;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DeepContextSummaryService {

    private static final String NODE = "context_summary";

    private final ModelGateway modelGateway;
    private final AgentRuntimeProperties properties;
    private final BudgetGuard budgetGuard;
    private final TraceRecorder traceRecorder;

    public DeepContextSummaryService(ModelGateway modelGateway,
                                     AgentRuntimeProperties properties,
                                     BudgetGuard budgetGuard,
                                     TraceRecorder traceRecorder) {
        this.modelGateway = modelGateway;
        this.properties = properties;
        this.budgetGuard = budgetGuard;
        this.traceRecorder = traceRecorder;
    }

    public DeepSummaryResult summarize(AgentContext context,
                                       List<String> transcriptEntries,
                                       long deadlineEpochMs) {
        if (modelGateway == null || transcriptEntries == null || transcriptEntries.isEmpty()) {
            throw new IllegalStateException("context summary model is unavailable");
        }
        int chunkChars = positive(contextProperties().getDeepSummaryChunkTokenLimit(), 12000)
                * positive(properties.getBudget().getEstimatedCharsPerToken(), 4);
        List<String> pending = packEntries(transcriptEntries, chunkChars);
        int maxCalls = positive(contextProperties().getDeepSummaryMaxCalls(), 8);
        int calls = 0;
        String actualModel = null;

        while (true) {
            List<String> summarized = new ArrayList<>();
            for (String chunk : pending) {
                if (++calls > maxCalls) {
                    throw new IllegalStateException("context summary call limit exceeded");
                }
                ModelChatResult result = summarizeChunk(context, chunk, deadlineEpochMs);
                summarized.add(result.getContent());
                actualModel = StringUtils.defaultIfBlank(result.getActualModel(), actualModel);
            }
            if (summarized.size() == 1) {
                return DeepSummaryResult.builder()
                        .summary(summarized.getFirst())
                        .model(actualModel)
                        .calls(calls)
                        .build();
            }
            pending = packEntries(summarized, chunkChars);
        }
    }

    private ModelChatResult summarizeChunk(AgentContext context, String chunk, long deadlineEpochMs) {
        int maxOutputTokens = positive(contextProperties().getDeepSummaryMaxOutputTokens(), 2048);
        String model = summaryModel();
        String promptText = """
                Summarize this agent transcript for a context restart.
                Preserve the user goal, constraints, decisions, files and tool evidence, current plan,
                unfinished work, failures, and every context artifact ID. Do not invent facts.
                Return concise plain text only.

                TRANSCRIPT:
                """ + chunk;
        if (budgetGuard != null) {
            BudgetCheckResult check = budgetGuard.checkBeforeModelCall(
                    context, NODE, model, ModelCallPurpose.CONTEXT_SUMMARY, promptText, maxOutputTokens);
            if (!check.isAllowed()) {
                throw new IllegalStateException("context summary exceeds remaining budget");
            }
        }
        long remainingMs = Math.max(1L, deadlineEpochMs - System.currentTimeMillis());
        ChatPrompt prompt = ChatPrompt.builder()
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .message(promptText)
                .model(model)
                .maxTokens(maxOutputTokens)
                .capability(ModelCapabilities.COMPLETE_CONTEXT_SUMMARY)
                .purpose(ModelCallPurpose.CONTEXT_SUMMARY)
                .deadlineEpochMs(deadlineEpochMs)
                .build();
        long startedAt = System.currentTimeMillis();
        try (ModelCallTraceContext.Scope ignored = ModelCallTraceContext.open(context)) {
            ModelChatResult result = modelGateway.complete(prompt)
                    .timeout(Duration.ofMillis(remainingMs))
                    .block(Duration.ofMillis(remainingMs + 100L));
            if (result == null || StringUtils.isBlank(result.getContent())) {
                throw new IllegalStateException("context summary response is empty");
            }
            if ("length".equalsIgnoreCase(result.getFinishReason())
                    || "max_tokens".equalsIgnoreCase(result.getFinishReason())) {
                throw new IllegalStateException("context summary response is truncated");
            }
            if (StringUtils.isBlank(result.getActualModel())) {
                result.setActualModel(model);
            }
            TraceCost cost = budgetGuard == null ? null
                    : budgetGuard.recordModelUsage(context, result.getActualModel(), result.getUsage());
            if (traceRecorder != null) {
                traceRecorder.recordModelUsage(context, NODE, result.getUsage(), cost,
                        Map.of("purpose", ModelCallPurpose.CONTEXT_SUMMARY.name()));
                traceRecorder.recordModelGatewayEvent(context, "context_summary_call", NODE, "success",
                        System.currentTimeMillis() - startedAt, "context summary completed", null,
                        Map.of("model", StringUtils.defaultString(result.getActualModel()),
                                "inputChars", chunk.length()));
            }
            return result;
        } catch (RuntimeException e) {
            if (traceRecorder != null) {
                traceRecorder.recordModelGatewayEvent(context, "context_summary_call", NODE, "failed",
                        System.currentTimeMillis() - startedAt, "context summary failed", e,
                        Map.of("model", StringUtils.defaultString(model), "inputChars", chunk.length()));
            }
            throw e;
        }
    }

    private List<String> packEntries(List<String> entries, int maxChars) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String entry : entries) {
            String value = StringUtils.defaultString(entry);
            if (value.length() > maxChars) {
                throw new IllegalStateException("single context entry exceeds summary chunk limit");
            }
            if (!current.isEmpty() && current.length() + value.length() + 2 > maxChars) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(value);
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private String summaryModel() {
        return StringUtils.defaultIfBlank(contextProperties().getDeepSummaryModel(),
                properties.getModelRecovery() == null ? null : properties.getModelRecovery().getContextFallbackModel());
    }

    private AgentRuntimeProperties.ContextProperties contextProperties() {
        if (properties.getContext() == null) {
            properties.setContext(new AgentRuntimeProperties.ContextProperties());
        }
        return properties.getContext();
    }

    private int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    @Value
    @Builder
    public static class DeepSummaryResult {

        String summary;
        String model;
        int calls;

    }

}
