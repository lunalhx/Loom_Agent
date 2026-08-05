package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.service.context.WorkingContextMemoryService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryInitializer;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.domain.tool.model.ToolOutputSanitization;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Single recording entry for tool results, validation errors, and approval
 * denials. Unifies cleaning, working memory, ledger, history, and the
 * Observation event. Ledger event keys stay idempotent so checkpoint resume
 * never re-executes tools and never duplicates records.
 */
public class ObservationNode extends AbstractAgentNode {

    private static final Logger log = LoggerFactory.getLogger(ObservationNode.class);

    private final ToolOutputSanitizer sanitizer;
    private final TraceRecorder traceRecorder;
    private final AgentMetrics agentMetrics;
    private final ConversationHistoryAppendService ledgerAppendService;
    private final WorkingContextMemoryService workingMemoryService;

    public ObservationNode(ToolOutputSanitizer sanitizer,
                           TraceRecorder traceRecorder,
                           AgentMetrics agentMetrics,
                           ConversationHistoryAppendService ledgerAppendService) {
        super(AgentNodeNames.OBSERVATION, List.of("toolResult", "decision", "toolSteps"));
        this.sanitizer = sanitizer;
        this.traceRecorder = traceRecorder;
        this.agentMetrics = agentMetrics;
        this.ledgerAppendService = ledgerAppendService;
        this.workingMemoryService = new WorkingContextMemoryService();
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        ToolResult result = context.getToolResult();
        appendStep(context, result != null && result.isSuccess());

        String toolName = context.getDecision() != null && context.getDecision().getTool() != null
                ? context.getDecision().getTool() : "unknown";
        String rawObservation = result != null ? result.getObservation() : "";
        if (rawObservation == null) {
            rawObservation = "";
        }

        ToolOutputSanitization sanitization = sanitizeObservation(context, toolName, rawObservation);

        workingMemoryService.onToolResult(context, toolName, result);

        if (ledgerAppendService != null) {
            String eventKey = ConversationHistoryInitializer.eventKey(
                    context.getRunId(), String.valueOf(Math.max(1, context.getToolSteps())), "tool_result");
            String toolInputJson = context.getDecision() != null && context.getDecision().getInput() != null
                    ? context.getDecision().getInput().toString() : null;
            ledgerAppendService.appendToolResult(context, sanitization.getOutput(), result,
                    toolName, toolInputJson, eventKey);
        }

        return NodeResult.nextRound(observationEvents(context));
    }

    private ToolOutputSanitization sanitizeObservation(AgentContext context, String toolName,
                                                       String rawObservation) {
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

        return sanitization;
    }
}