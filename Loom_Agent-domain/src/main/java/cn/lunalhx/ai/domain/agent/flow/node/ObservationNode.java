package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentStep;
import cn.lunalhx.ai.domain.agent.service.context.WorkingContextMemoryService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryInitializer;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.domain.tool.model.ToolOutputSanitization;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.service.ExecutionWindowTools;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Tool output node ({@code tool_output}). The single recording entry for tool
 * results, validation errors and approval denials.
 *
 * <p>Consumes the already-sanitized {@link ToolResult} produced by the
 * executor as the primary boundary; the sanitizer here is a defensive
 * idempotency check, not the only sanitizer. It unifies history, working
 * memory, ledger, the observation event and security events. It never calls
 * the underlying tool and never re-approves/re-validates input.
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
        super(AgentNodeNames.TOOL_OUTPUT, List.of("toolResult", "decision", "toolSteps"));
        this.sanitizer = sanitizer;
        this.traceRecorder = traceRecorder;
        this.agentMetrics = agentMetrics;
        this.ledgerAppendService = ledgerAppendService;
        this.workingMemoryService = new WorkingContextMemoryService();
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        ToolResult result = context.getToolResult();
        appendSafeStep(context, result != null && result.isSuccess());

        String toolName = context.getDecision() != null && context.getDecision().getTool() != null
                ? context.getDecision().getTool() : "unknown";

        ToolOutputSanitization sanitization = sanitizeObservation(context, toolName, result);
        String safeOutput = sanitization.getOutput();
        if (result != null && result.getObservation() != null && !safeOutput.equals(result.getObservation())) {
            // defensive idempotency: the executor already sanitized; keep the
            // safe value everywhere so no exit ever sees raw data.
            result.setObservation(safeOutput);
        }

        workingMemoryService.onToolResult(context, toolName, result);

        if (ledgerAppendService != null) {
            String toolCallId = context.getToolCall() == null ? null : context.getToolCall().getToolCallId();
            String resultIdentity = ExecutionWindowTools.requiresWindow(toolName)
                    && StringUtils.isNotBlank(toolCallId)
                    ? toolCallId
                    : String.valueOf(Math.max(1, context.getToolSteps()));
            String eventKey = ConversationHistoryInitializer.eventKey(
                    context.getRunId(), resultIdentity, "tool_result");
            String toolInputJson = context.getDecision() != null && context.getDecision().getInput() != null
                    ? context.getDecision().getInput().toString() : null;
            ledgerAppendService.appendToolResult(context, safeOutput, result,
                    toolName, toolInputJson, eventKey);
        }

        return NodeResult.nextRound(observationEvents(context));
    }

    private ToolOutputSanitization sanitizeObservation(AgentContext context, String toolName,
                                                       ToolResult result) {
        String raw = result == null ? "" : result.getObservation();
        ToolOutputSanitization sanitization;
        try {
            sanitization = sanitizer.sanitize(toolName, raw == null ? "" : raw);
        } catch (Exception e) {
            // Fail-closed: never fall back to raw output on sanitizer failure.
            log.warn("Tool output sanitization failed for tool={}", toolName, e);
            sanitization = ToolOutputSanitization.degraded(
                    "tool_error: sanitization_failed - output withheld");
            if (traceRecorder != null) {
                traceRecorder.recordSecurityEvent(context, "sanitization_failed",
                        AgentNodeNames.TOOL_OUTPUT, "error",
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
                        AgentNodeNames.TOOL_OUTPUT, "warning",
                        Map.of(
                                "tool", toolName,
                                "matchCount", matchCount,
                                "matchedRuleIds", sanitization.getMatchedRuleIds(),
                                "outputChars", sanitization.getOutput().length()));
            }
        }

        return sanitization;
    }

    private void appendSafeStep(AgentContext context, boolean success) {
        AgentStep step = AgentStep.builder()
                .toolStep(Math.max(1, context.getToolSteps()))
                .thought(context.getDecision() == null ? null : context.getDecision().getReason())
                .tool(context.getDecision() == null ? "model_parse" : context.getDecision().getTool())
                .input(context.getDecision() == null || context.getDecision().getInputView() == null
                        ? null : String.valueOf(context.getDecision().getInputView()))
                .observation(context.getToolResult() == null ? null : context.getToolResult().getObservation())
                .success(success)
                .build();
        context.runtime().history().add(step);
    }
}
