package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentPlan;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ReplanReason;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.OutputFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.util.List;

public class ReplanNode extends AbstractAgentNode {

    private final ModelGateway modelGateway;
    private final AgentRuntimeProperties properties;
    private final ObjectMapper objectMapper;

    public ReplanNode(ModelGateway modelGateway, AgentRuntimeProperties properties, ObjectMapper objectMapper) {
        super(AgentNodeNames.REPLAN, List.of("plan", "replanReason", "history"));
        this.modelGateway = modelGateway;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        if (context.getPlan() == null) {
            context.setPlan(AgentPlan.forQuestion(context.getQuestion()));
        }
        ReplanReason reason = context.getReplanReason() == null ? ReplanReason.TOOL_FAILURE : context.getReplanReason();
        boolean modelUpdated = applyModelPlanDelta(context, reason);
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

    private boolean applyModelPlanDelta(AgentContext context, ReplanReason reason) {
        try {
            ChatPrompt prompt = ChatPrompt.builder()
                    .requestId(context.getRequestId())
                    .conversationId(context.getConversationId())
                    .message(renderReplanPrompt(context, reason))
                    .outputFormat(OutputFormat.JSON_OBJECT)
                    .build();
            ModelChatResult result = modelGateway.complete(prompt)
                    .timeout(Duration.ofMillis(properties.getStepTimeoutMs()))
                    .block(Duration.ofMillis(properties.getStepTimeoutMs() + 1000L));
            if (result == null || StringUtils.isBlank(result.getContent())) {
                return false;
            }
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
            return false;
        }
    }

    private String renderReplanPrompt(AgentContext context, ReplanReason reason) {
        return "你是代码 Agent 的重规划器。只能输出 JSON 对象，格式为 "
                + "{\"todos\":[{\"id\":\"task-1\",\"content\":\"...\",\"status\":\"pending|in_progress|completed|blocked|skipped\",\"evidence\":\"可选\",\"blocker\":\"可选\"}]}。\n"
                + "不要删除历史任务；只能更新状态或追加任务。\n"
                + "用户任务：" + context.getQuestion() + "\n"
                + "重规划原因：" + reason + "\n"
                + "失败信息：" + StringUtils.defaultString(context.getReplanMessage()) + "\n"
                + "当前计划：\n" + context.getPlan().render() + "\n";
    }

    private String fallbackItem(ReplanReason reason) {
        return switch (reason) {
            case UNSAFE_RESUME -> "恢复后先检查当前文件状态和测试状态，避免重复执行可能已生效的写操作";
            case APPROVAL_REJECTED -> "根据用户拒绝原因选择只读解释、替代方案或更小范围修改";
            case POLICY_DENIED -> "绕开被策略拦截的高危动作，选择安全的只读检查或人工说明";
            case INCOMPLETE_PLAN -> "补齐尚未完成的计划项，再输出最终结论";
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

}
