package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.domain.tool.model.ToolOutputSanitization;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class ObservationNode extends AbstractAgentNode {

    private static final Logger log = LoggerFactory.getLogger(ObservationNode.class);

    private final ToolOutputSanitizer sanitizer;
    private final TraceRecorder traceRecorder;
    private final AgentMetrics agentMetrics;

    public ObservationNode(ToolOutputSanitizer sanitizer,
                           TraceRecorder traceRecorder,
                           AgentMetrics agentMetrics) {
        super(AgentNodeNames.OBSERVATION, List.of("toolResult", "decision", "step", "dynamicText"));
        this.sanitizer = sanitizer;
        this.traceRecorder = traceRecorder;
        this.agentMetrics = agentMetrics;
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        ToolResult result = context.getToolResult();
        appendStep(context, result != null && result.isSuccess());
        context.getDynamicText().appendToolResult(
                Math.max(1, context.getStep()),
                name(),
                context.getDecision(),
                result,
                toDynamicObservation(context, result));
        return NodeResult.next(AgentNodeNames.REPLAN_GUARD, observationEvents(context));
    }

    private String toDynamicObservation(AgentContext context, ToolResult result) {
        String toolName = context.getDecision() != null && context.getDecision().getTool() != null
                ? context.getDecision().getTool() : "unknown";
        String rawObservation = result != null ? result.getObservation() : "";
        if (rawObservation == null) {
            rawObservation = "";
        }

        ToolOutputSanitization sanitization;
        try {
            sanitization = sanitizer.sanitize(toolName, rawObservation);
        } catch (Exception e) {
            log.warn("Prompt injection scan failed for tool={}", toolName, e);
            sanitization = ToolOutputSanitization.clean(rawObservation);
            if (traceRecorder != null) {
                traceRecorder.recordSecurityEvent(context, "prompt_injection_scan_failed",
                        AgentNodeNames.OBSERVATION, "error",
                        Map.of("tool", toolName));
            }
        }

        if (sanitization.isInjectionDetected()) {
            int matchCount = sanitization.getMatchCount();
            if (agentMetrics != null) {
                agentMetrics.recordPromptInjectionDetected(toolName, matchCount);
            }
            if (traceRecorder != null) {
                traceRecorder.recordSecurityEvent(context, "prompt_injection_detected",
                        AgentNodeNames.OBSERVATION, "warning",
                        Map.of(
                                "tool", toolName,
                                "matchCount", matchCount,
                                "matchedRuleIds", sanitization.getMatchedRuleIds(),
                                "outputChars", sanitization.getOutput().length()));
            }
        }

        String escapedToolName = escapeXmlAttr(toolName);

        StringBuilder text = new StringBuilder();
        text.append("Success: ").append(result != null && result.isSuccess()).append('\n');
        if (sanitization.isInjectionDetected()) {
            text.append("[security_note] 检测到疑似注入指令，已标记为不可信数据\n");
        }
        text.append("<untrusted_tool_output tool=\"").append(escapedToolName).append("\">\n");
        text.append(sanitization.getOutput()).append('\n');
        text.append("</untrusted_tool_output>");
        return text.toString();
    }

    private static String escapeXmlAttr(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

}
