package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentPlan;
import cn.lunalhx.ai.domain.agent.model.entity.BudgetCheckResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ReplanReason;
import cn.lunalhx.ai.domain.agent.model.valobj.TraceCost;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelCapabilities;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.OutputFormat;
import cn.lunalhx.ai.domain.agent.service.ModelCallTraceContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
public class ReplanNode extends AbstractAgentNode {

    private final ModelGateway modelGateway;
    private final AgentRuntimeProperties properties;
    private final ObjectMapper objectMapper;
    private final TraceRecorder traceRecorder;
    private final BudgetGuard budgetGuard;

    public ReplanNode(ModelGateway modelGateway, AgentRuntimeProperties properties, ObjectMapper objectMapper) {
        this(modelGateway, properties, objectMapper, null, null);
    }

    public ReplanNode(ModelGateway modelGateway,
                      AgentRuntimeProperties properties,
                      ObjectMapper objectMapper,
                      TraceRecorder traceRecorder,
                      BudgetGuard budgetGuard) {
        super(AgentNodeNames.REPLAN, List.of("plan", "replanReason", "history"));
        this.modelGateway = modelGateway;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.traceRecorder = traceRecorder;
        this.budgetGuard = budgetGuard;
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        if (context.getPlan() == null) {
            context.setPlan(AgentPlan.forQuestion(context.getQuestion()));
        }
        ReplanReason reason = context.getReplanReason() == null ? ReplanReason.TOOL_FAILURE : context.getReplanReason();
        String prompt = renderReplanPrompt(context, reason);
        if (budgetGuard != null) {
            BudgetCheckResult check = budgetGuard.checkBeforeModelCall(context, name(), prompt);
            if (!check.isAllowed()) {
                blockForBudget(context, check);
                return NodeResult.next(AgentNodeNames.FAIL, List.of());
            }
        }
        boolean modelUpdated = applyModelPlanDelta(context, prompt);
        if (!modelUpdated) {
            context.getPlan().addReplanItem(fallbackItem(reason), "replan:" + reason.name());
        }
        context.getDynamicText().appendSystemNote(
                Math.max(1, context.getStep()),
                name(),
                "Replan",
                "Reason: " + reason + "\n"
                        + "PlanDeltaSource: " + (modelUpdated ? "model" : "fallback") + "\n"
                        + StringUtils.defaultString(context.getReplanMessage()));
        AgentEvent replanStarted = event(context, AgentEventType.REPLAN_STARTED)
                .plan(context.getPlan().toView())
                .message(context.getReplanMessage())
                .build();
        AgentEvent planUpdated = event(context, AgentEventType.PLAN_UPDATED)
                .plan(context.getPlan().toView())
                .build();
        context.setReplanReason(null);
        context.setReplanMessage(null);
        return NodeResult.next(AgentNodeNames.RENDER_PROMPT, List.of(replanStarted, planUpdated));
    }

    private boolean applyModelPlanDelta(AgentContext context, String promptText) {
        long startedAt = System.currentTimeMillis();
        try {
            long deadlineEpochMs = System.currentTimeMillis() + properties.getStepTimeoutMs();
            int maxTokens = 0;
            ModelChatResult result = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                if (budgetGuard != null) {
                    BudgetCheckResult check = budgetGuard.checkBeforeModelCall(context, name(), null,
                            ModelCallPurpose.CONTROL_JSON, promptText, maxTokens);
                    if (!check.isAllowed()) {
                        blockForBudget(context, check);
                        return false;
                    }
                }
                ChatPrompt prompt = ChatPrompt.builder()
                        .requestId(context.getRequestId())
                        .conversationId(context.getConversationId())
                        .message(promptText)
                        .model(context.getCurrentModel())
                        .maxTokens(maxTokens <= 0 ? null : maxTokens)
                        .capability(ModelCapabilities.COMPLETE_REPLAN)
                        .purpose(ModelCallPurpose.CONTROL_JSON)
                        .deadlineEpochMs(deadlineEpochMs)
                        .outputFormat(OutputFormat.JSON_OBJECT)
                        .build();
                try (ModelCallTraceContext.Scope ignored = ModelCallTraceContext.open(context)) {
                    long remainingMs = Math.max(1L, deadlineEpochMs - System.currentTimeMillis());
                    result = modelGateway.complete(prompt)
                            .timeout(Duration.ofMillis(remainingMs))
                            .block(Duration.ofMillis(remainingMs + 100L));
                }
                if (result == null || !isLength(result.getFinishReason())) {
                    break;
                }
                recordUsage(context, result);
                if (attempt == 0) {
                    maxTokens = escalatedMaxTokens();
                }
            }
            if (result == null || StringUtils.isBlank(result.getContent())) {
                return false;
            }
            if (isLength(result.getFinishReason())) {
                if (traceRecorder != null) {
                    traceRecorder.recordModelGatewayEvent(context, "model_recovery_exhausted", name(), "error", 0L,
                            ModelErrorCode.MODEL_DECISION_TRUNCATED.message(), null,
                            Map.of("purpose", ModelCallPurpose.CONTROL_JSON.name(),
                                    "errorCode", ModelErrorCode.MODEL_DECISION_TRUNCATED.code()));
                }
                return false;
            }
            recordUsage(context, result);
            JsonNode root = objectMapper.readTree(stripMarkdownFence(result.getContent()));
            JsonNode todos = root.path("todos");
            if (!todos.isArray()) {
                todos = root.path("items");
            }
            if (!todos.isArray() || todos.isEmpty()) {
                return false;
            }
            context.getPlan().applyTodoWrite(objectMapper.createObjectNode().set("todos", todos));
            return true;
        } catch (Exception e) {
            log.warn("Replan model call failed, fallback to generic item", e);
            if (traceRecorder != null) {
                long durationMs = System.currentTimeMillis() - startedAt;
                traceRecorder.recordModelGatewayEvent(context,
                        "model_replan_call_failed", name(), "error", durationMs,
                        "Replan model call failed, fallback to generic item", e,
                        Map.of("purpose", ModelCallPurpose.CONTROL_JSON.name(),
                                "capability", ModelCapabilities.COMPLETE_REPLAN,
                                "fallback", "generic_item",
                                "replanReason", context.getReplanReason() != null
                                        ? context.getReplanReason().name()
                                        : ReplanReason.TOOL_FAILURE.name()));
            }
            return false;
        }
    }

    private String renderReplanPrompt(AgentContext context, ReplanReason reason) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是代码 Agent 的重规划器。只能输出 JSON 对象，格式为 "
                + "{\"todos\":[{\"id\":\"task-1\",\"content\":\"...\",\"status\":\"pending|in_progress|completed|blocked|skipped\",\"evidence\":\"可选\",\"blocker\":\"可选\"}]}。\n");
        prompt.append("不要删除历史任务；只能更新状态或追加任务。\n");
        prompt.append("用户任务：").append(context.getQuestion()).append("\n");
        prompt.append("重规划原因：").append(reason).append("\n");
        if (reason == ReplanReason.STEP_BUDGET_CONTINUATION) {
            prompt.append("当前是第 ").append(context.getSegmentIndex() + 1).append("/")
                    .append(context.getMaxSegments()).append(" 段执行。请总结已完成内容，选择下一步方向，"
                    + "不要重复上一段最后的动作。\n");
        }
        prompt.append("失败信息：").append(StringUtils.defaultString(context.getReplanMessage())).append("\n");
        prompt.append("当前计划：\n").append(context.getPlan().render()).append("\n");
        return prompt.toString();
    }

    private String fallbackItem(ReplanReason reason) {
        return switch (reason) {
            case UNSAFE_RESUME -> "恢复后先检查当前文件状态和测试状态，避免重复执行可能已生效的写操作";
            case APPROVAL_REJECTED -> "根据用户拒绝原因选择只读解释、替代方案或更小范围修改";
            case POLICY_DENIED -> "绕开被策略拦截的高危动作，选择安全的只读检查或人工说明";
            case INCOMPLETE_PLAN -> "补齐尚未完成的计划项，再输出最终结论";
            case STEP_BUDGET_CONTINUATION -> "总结已完成内容，选择下一步方向，避免重复上一段最后的动作";
            case REPEATED_ACTION -> "检测到重复动作无进展，换一种方式或工具继续任务";
            default -> "检查失败原因，修复问题后重新运行必要验证";
        };
    }

    private String stripMarkdownFence(String output) {
        String text = StringUtils.trimToEmpty(output);
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "");
            text = text.replaceFirst("\\s*```$", "");
        }
        return text;
    }

    private void recordUsage(AgentContext context, ModelChatResult result) {
        TraceCost cost = budgetGuard == null ? null
                : budgetGuard.recordModelUsage(context, result.getActualModel(), result.getUsage());
        if (traceRecorder != null) {
            Map<String, Object> metadata = result.getUsage() == null
                    ? Map.of("usageMissing", true)
                    : Map.of("finishReason", StringUtils.defaultString(result.getFinishReason()));
            traceRecorder.recordModelUsage(context, name(), result.getUsage(), cost, metadata);
        }
    }

    private boolean isLength(String finishReason) {
        return "length".equalsIgnoreCase(StringUtils.trimToEmpty(finishReason))
                || "max_tokens".equalsIgnoreCase(StringUtils.trimToEmpty(finishReason));
    }

    private int escalatedMaxTokens() {
        Integer value = properties.getModelRecovery() == null
                ? null : properties.getModelRecovery().getEscalatedMaxTokens();
        return value == null || value <= 0 ? 8192 : value;
    }

    private void blockForBudget(AgentContext context, BudgetCheckResult check) {
        String reason = "budget_exceeded: usedTokens=" + check.getUsedTokens()
                + ", estimatedInputTokens=" + check.getEstimatedInputTokens()
                + ", reservedOutputTokens=" + check.getReservedOutputTokens()
                + ", maxTotalTokens=" + check.getMaxTotalTokens();
        context.setBudgetBlockedReason(reason);
        fail(context, AgentStopReason.BUDGET_EXCEEDED, "budget_exceeded", reason);
    }

}
