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
import cn.lunalhx.ai.domain.agent.model.valobj.AgentPlanItemStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ReplanReason;
import cn.lunalhx.ai.domain.agent.model.valobj.TodoApplyResult;
import cn.lunalhx.ai.domain.agent.model.valobj.TraceCost;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelCapabilities;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.OutputFormat;
import cn.lunalhx.ai.domain.agent.service.observability.ModelCallTraceContext;
import cn.lunalhx.ai.domain.agent.service.observability.ModelCallTraceLabels;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerInitializer;
import cn.lunalhx.ai.domain.agent.service.ledger.ControlUpdateTexts;
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
    private final ConversationLedgerAppendService ledgerAppendService;

    public ReplanNode(ModelGateway modelGateway, AgentRuntimeProperties properties, ObjectMapper objectMapper) {
        this(modelGateway, properties, objectMapper, null, null, null);
    }

    public ReplanNode(ModelGateway modelGateway,
                      AgentRuntimeProperties properties,
                      ObjectMapper objectMapper,
                      TraceRecorder traceRecorder,
                      BudgetGuard budgetGuard) {
        this(modelGateway, properties, objectMapper, traceRecorder, budgetGuard, null);
    }

    public ReplanNode(ModelGateway modelGateway,
                      AgentRuntimeProperties properties,
                      ObjectMapper objectMapper,
                      TraceRecorder traceRecorder,
                      BudgetGuard budgetGuard,
                      ConversationLedgerAppendService ledgerAppendService) {
        super(AgentNodeNames.REPLAN, List.of("plan", "replanReason", "history"));
        this.modelGateway = modelGateway;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.traceRecorder = traceRecorder;
        this.budgetGuard = budgetGuard;
        this.ledgerAppendService = ledgerAppendService;
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
        List<TodoApplyResult> results = applyModelPlanDelta(context, prompt);
        if (results == null) {
            context.getPlan().addReplanItem(fallbackItem(reason), "replan:" + reason.name());
        } else if (results.stream().anyMatch(TodoApplyResult::isApplied)) {
            context.setNoProgressRounds(0);
            if (traceRecorder != null) {
                traceRecorder.recordModelGatewayEvent(context, "replan_applied", name(), "ok", 0L,
                        "Model replan applied " + results.stream().filter(TodoApplyResult::isApplied).count() + " items",
                        null, Map.of("appliedCount", results.stream().filter(TodoApplyResult::isApplied).count(),
                                "skippedCount", results.size() - results.stream().filter(TodoApplyResult::isApplied).count()));
            }
        } else {
            int rounds = context.getNoProgressRounds() + 1;
            context.setNoProgressRounds(rounds);
            if (traceRecorder != null) {
                traceRecorder.recordModelGatewayEvent(context, "replan_no_change", name(), "warn", 0L,
                        "Replan produced no applicable changes (round " + rounds + ")",
                        null, Map.of("noProgressRound", rounds, "reason", reason.name()));
            }
            AgentRuntimeProperties runProperties = context.runtimeProperties(properties);
            int maxRounds = runProperties.getStepBudget() != null
                    ? Math.max(1, runProperties.getStepBudget().getNoProgressMaxRounds() == null
                            ? 3 : runProperties.getStepBudget().getNoProgressMaxRounds())
                    : 3;
            if (rounds >= maxRounds) {
                context.setStopReason(AgentStopReason.NO_PROGRESS);
                context.setErrorCode("replan_no_progress");
                if (traceRecorder != null) {
                    traceRecorder.recordModelGatewayEvent(context, "replan_no_progress_terminated", name(), "error", 0L,
                            "Replan loop terminated after " + rounds + " consecutive no-op rounds",
                            null, Map.of("maxNoProgressRounds", maxRounds, "reason", reason.name()));
                }
                return NodeResult.next(AgentNodeNames.FAIL, List.of(
                        event(context, AgentEventType.PLAN_UPDATED)
                                .plan(context.getPlan().toView())
                                .build()));
            }
        }

        boolean modelUpdated = results != null;
        appendPlanSnapshotIfChanged(context);
        appendReplanNote(context, reason, modelUpdated);
        if (ledgerAppendService != null) {
            ledgerAppendService.appendSystemNote(context,
                    "Reason: " + reason + "\n"
                            + "PlanDeltaSource: " + (modelUpdated ? "model" : "fallback") + "\n"
                            + StringUtils.defaultString(context.getReplanMessage()),
                    ConversationLedgerInitializer.eventKey(context.getRunId(),
                            String.valueOf(Math.max(1, context.getStep())), "replan_note"));
        }
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

    private List<TodoApplyResult> applyModelPlanDelta(AgentContext context, String promptText) {
        try {
        long deadlineEpochMs = System.currentTimeMillis()
                + context.runtimeProperties(properties).getStepTimeoutMs();
        int maxTokens = 0;
        ModelChatResult result = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (budgetGuard != null) {
                BudgetCheckResult check = budgetGuard.checkBeforeModelCall(context, name(), null,
                        ModelCallPurpose.CONTROL_JSON, promptText, maxTokens);
                if (!check.isAllowed()) {
                    blockForBudget(context, check);
                    return null;
                }
            }
            try {
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
                        .runtimeProperties(context.getRunConfig() == null ? null : context.getRunConfig().model())
                        .build();
                try (ModelCallTraceContext.Scope ignored = ModelCallTraceContext.open(context)) {
                    long remainingMs = Math.max(1L, deadlineEpochMs - System.currentTimeMillis());
                    result = modelGateway.complete(prompt)
                            .timeout(Duration.ofMillis(remainingMs))
                            .block(Duration.ofMillis(remainingMs + 100L));
                }
            } catch (Exception e) {
                log.warn("Replan model call failed, fallback to generic item", e);
                if (traceRecorder != null) {
                    traceRecorder.recordModelGatewayEvent(context,
                            "model_replan_call_failed", name(), "error", 0L,
                            "Replan model call failed, fallback to generic item", e,
                            Map.of("purpose", ModelCallPurpose.CONTROL_JSON.name(),
                                    "capability", ModelCapabilities.COMPLETE_REPLAN));
                }
                return null;
            }
            if (result == null || !isLength(result.getFinishReason())) {
                break;
            }
            recordUsage(context, result);
            if (attempt == 0) {
                maxTokens = escalatedMaxTokens(context);
            }
        }
        if (result == null || StringUtils.isBlank(result.getContent())) {
            return null;
        }
        if (isLength(result.getFinishReason())) {
            if (traceRecorder != null) {
                traceRecorder.recordModelGatewayEvent(context, "model_recovery_exhausted", name(), "error", 0L,
                        ModelErrorCode.MODEL_DECISION_TRUNCATED.defaultMessage(), null,
                        Map.of("purpose", ModelCallPurpose.CONTROL_JSON.name(),
                                "errorCode", ModelErrorCode.MODEL_DECISION_TRUNCATED.code()));
            }
            return null;
        }
        recordUsage(context, result);
        try {
            JsonNode root = objectMapper.readTree(stripMarkdownFence(result.getContent()));
            JsonNode todos = root.path("todos");
            if (!todos.isArray()) {
                todos = root.path("items");
            }
            if (!todos.isArray() || todos.isEmpty()) {
                return null;
            }
            return context.getPlan().applyTodoWriteForReplan(
                    objectMapper.createObjectNode().set("todos", todos),
                    context.getReplanReason(),
                    context.getTouchedFiles());
        } catch (Exception e) {
            log.warn("Replan JSON parse failed", e);
            if (traceRecorder != null) {
                traceRecorder.recordModelGatewayEvent(context,
                        "model_replan_parse_failed", name(), "error", 0L,
                        "Replan JSON parse failed", e,
                        Map.of("purpose", ModelCallPurpose.CONTROL_JSON.name(),
                                "capability", ModelCapabilities.COMPLETE_REPLAN));
            }
            return null;
        }
        } catch (Exception e) {
            log.warn("Replan model plan delta failed, fallback to generic item", e);
            return null;
        }
    }

    private String renderReplanPrompt(AgentContext context, ReplanReason reason) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是代码 Agent 的重规划器。只能输出 JSON 对象。\n");
        prompt.append("优先关闭、阻塞或跳过无关/错误验证导致的任务，不要扩展用户目标。\n");
        prompt.append("不要为同一 targets/command 创建新任务；如果任务已存在，必须用已有 id 更新，不要换 wording 创建重复任务。\n");
        prompt.append("不要删除历史记录，但也不要无限扩展任务范围。\n");
        prompt.append("新任务只在以下情况创建：原计划缺少用户明确要求的交付物；工具结果证明存在未覆盖的目标文件或必要步骤；恢复中断后的安全只读检查。\n");
        prompt.append("新任务必须带 parentId 或 derivedFrom，指向某个已有任务或工具失败事件。\n");
        prompt.append("保留现有任务的 kind 和 targets 字段。\n");
        prompt.append("格式: {\"todos\":[{\"id\":\"task-1\",\"content\":\"...\",\"status\":\"pending|in_progress|completed|blocked|skipped\",\"kind\":\"inspect|edit|verify\",\"targets\":[\"相对路径\"],\"evidence\":\"可选完成证据\",\"blocker\":\"可选阻塞原因\",\"verification\":{\"command\":\"...\",\"passed\":true,\"exitCode\":0,\"summary\":\"...\"},\"derivedFrom\":\"源任务id\",\"parentId\":\"父任务id\"}]}\n");
        prompt.append("\n用户任务：").append(context.getQuestion()).append("\n");
        prompt.append("重规划原因：").append(reason).append("\n");
        if (reason == ReplanReason.STEP_BUDGET_CONTINUATION) {
            prompt.append("当前是第 ").append(context.getSegmentIndex() + 1).append("/")
                    .append(context.getMaxSegments()).append(" 段执行。请总结已完成内容，继续未完成的用户目标，"
                    + "不要扩展目标或重复上一段最后的动作。\n");
        }
        prompt.append("失败信息：").append(StringUtils.defaultString(context.getReplanMessage())).append("\n");
        if (StringUtils.contains(context.getReplanMessage(), "策略变更要求")
                || StringUtils.contains(context.getReplanMessage(), "必须更换策略")) {
            prompt.append("\n警告: 之前的策略已失败。必须在输出中说明本次策略与上次有何不同，不能只重复同一个工具和输入。\n");
        }
        prompt.append("当前计划：\n").append(context.getPlan().renderFull()).append("\n");
        long blockedCount = context.getPlan().getItems().stream()
                .filter(item -> item.getStatus() == AgentPlanItemStatus.BLOCKED)
                .count();
        if (blockedCount > 0) {
            prompt.append("\n注意: 已有 ").append(blockedCount)
                    .append(" 个任务被标记为 blocked，无需重复尝试。请聚焦其他未完成任务。\n");
        }
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
            Map<String, Object> extras = result.getUsage() == null
                    ? null
                    : Map.of("finishReason", StringUtils.defaultString(result.getFinishReason()));
            Map<String, Object> metadata = ModelCallTraceLabels.buildUsageMetadata(context, name(),
                    ModelCapabilities.COMPLETE_REPLAN, ModelCallPurpose.CONTROL_JSON,
                    result.getActualModel(), result.getUsage(), extras);
            traceRecorder.recordModelUsage(context, name(), result.getUsage(), cost, metadata);
        }
    }

    private boolean isLength(String finishReason) {
        return "length".equalsIgnoreCase(StringUtils.trimToEmpty(finishReason))
                || "max_tokens".equalsIgnoreCase(StringUtils.trimToEmpty(finishReason));
    }

    private int escalatedMaxTokens(AgentContext context) {
        AgentRuntimeProperties runProperties = context.runtimeProperties(properties);
        Integer value = runProperties.getModelRecovery() == null
                ? null : runProperties.getModelRecovery().getEscalatedMaxTokens();
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

    private void appendPlanSnapshotIfChanged(AgentContext context) {
        if (ledgerAppendService == null) {
            return;
        }
        if (context.getPlan() == null) {
            return;
        }
        int currentVersion = context.getPlan().getVersion();
        if (currentVersion <= context.getLastLedgerPlanVersion()) {
            return;
        }
        String text = ControlUpdateTexts.renderPlanSnapshot(context.getPlan());
        String eventKey = ConversationLedgerInitializer.eventKey(
                context.getRunId(), "plan", "v" + currentVersion);
        ledgerAppendService.appendControlUpdate(context, text, eventKey);
        context.setLastLedgerPlanVersion(currentVersion);
    }

    private void appendReplanNote(AgentContext context, ReplanReason reason, boolean modelUpdated) {
        if (ledgerAppendService == null) {
            return;
        }
        String text = ControlUpdateTexts.renderReplanNote(reason, modelUpdated,
                context.getReplanMessage());
        String eventKey = ConversationLedgerInitializer.eventKey(
                context.getRunId(), String.valueOf(Math.max(1, context.getStep())), "replan");
        ledgerAppendService.appendControlUpdate(context, text, eventKey);
    }

}
