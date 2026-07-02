package cn.lunalhx.ai.domain.agent.service.observability;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRole;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 共享的 trace metadata 构造器：把模型调用的低基数标签放进 metadata map，
 * 让 trace、metrics 都能从同一来源解析 call_site / agent_role 等维度。
 *
 * <p>不向 metadata 写入任何高基数字段（runId/traceId/requestId/conversationId/workspace/用户输入等）。
 */
public final class ModelCallTraceLabels {

    private ModelCallTraceLabels() {
    }

    /**
     * 构造 recordModelUsage 的 metadata：包含 model / capability / purpose / node / agentRole。
     * 若 usage 缺失则用 {@code usageMissing=true} 标记，与既有契约保持一致。
     *
     * @param context     Agent 调用上下文（可能为 null：例如 memory extraction 走后台 worker 没有 AgentContext）
     * @param node        调用点（model_call / replan / context_summary / memory_extraction 等）
     * @param capability  路由层 capability（complete.agent_decision / stream.chat / complete.context_summary 等）
     * @param purpose     业务目的（CONTROL_JSON / CONTEXT_SUMMARY / MEMORY_EXTRACTION / ...）
     * @param actualModel 实际触发的模型（含 fallback 切换后的真实模型；可为空）
     * @param usage       provider 返回的 usage（可能为 null）
     * @param extras      其它要追加的低基数 key/value（如 finishReason）
     */
    public static Map<String, Object> buildUsageMetadata(AgentContext context,
                                                         String node,
                                                         String capability,
                                                         ModelCallPurpose purpose,
                                                         String actualModel,
                                                         Object usage,
                                                         Map<String, Object> extras) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("node", safe(node));
        metadata.put("capability", safe(capability));
        metadata.put("purpose", purpose == null ? "none" : purpose.name());
        metadata.put("model", safe(actualModel));
        metadata.put("agentRole", agentRoleTag(context));
        if (usage == null) {
            metadata.put("usageMissing", Boolean.TRUE);
        }
        if (extras != null) {
            for (Map.Entry<String, Object> entry : extras.entrySet()) {
                if (entry.getValue() != null) {
                    metadata.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }
        return metadata;
    }

    /**
     * 为 cache usage 指标返回低基数 agent_role 标签值。
     * 把 "agentRole + sub/root" 编码进单一 tag，避免引入第二个高基数字段。
     */
    public static String agentRoleTag(AgentContext context) {
        if (context == null) {
            return "none";
        }
        AgentRole role = context.getAgentRole();
        boolean isSub = context.getParentRunId() != null;
        String name = role == null ? "none" : role.name();
        return isSub ? name + ".sub" : name;
    }

    public static String callSiteTag(String node) {
        return safe(node);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }
}
