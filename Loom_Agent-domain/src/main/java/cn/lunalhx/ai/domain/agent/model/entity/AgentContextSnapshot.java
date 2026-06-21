package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ReplanReason;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.model.WorkspaceRef;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentContextSnapshot {

    private String runId;
    private String requestId;
    private String conversationId;
    private String question;
    private String resolvedWorkspace;
    private WorkspaceRef workspace;
    private String workspaceDisplayName;
    private Integer maxSteps;
    private Integer step;
    private Integer parseErrors;
    private Instant startedAt;
    private List<ToolSpec> toolSpecs;
    private List<AgentStep> history;
    private List<DynamicTextEntry> dynamicTextEntries;
    private String currentPrompt;
    private String modelOutput;
    private AgentDecision decision;
    private ToolResult toolResult;
    private String finalAnswer;
    private AgentStopReason stopReason;
    private String errorCode;
    private String errorMessage;
    private AgentPlan plan;
    private ReplanReason replanReason;
    private String replanMessage;
    private String currentNode;
    private Long checkpointVersion;
    private Boolean unsafeResumeRequired;
    private String pendingApprovalId;

    public static AgentContextSnapshot from(AgentContext context) {
        return AgentContextSnapshot.builder()
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .question(context.getQuestion())
                .resolvedWorkspace(context.getResolvedWorkspace() == null ? null : context.getResolvedWorkspace().toString())
                .workspace(context.getWorkspace())
                .workspaceDisplayName(context.getWorkspaceDisplayName())
                .maxSteps(context.getMaxSteps())
                .step(context.getStep())
                .parseErrors(context.getParseErrors())
                .startedAt(context.getStartedAt())
                .toolSpecs(new ArrayList<>(context.getToolSpecs()))
                .history(new ArrayList<>(context.getHistory()))
                .dynamicTextEntries(context.getDynamicText().entries())
                .currentPrompt(context.getCurrentPrompt())
                .modelOutput(context.getModelOutput())
                .decision(context.getDecision())
                .toolResult(context.getToolResult())
                .finalAnswer(context.getFinalAnswer())
                .stopReason(context.getStopReason())
                .errorCode(context.getErrorCode())
                .errorMessage(context.getErrorMessage())
                .plan(context.getPlan())
                .replanReason(context.getReplanReason())
                .replanMessage(context.getReplanMessage())
                .currentNode(context.getCurrentNode())
                .checkpointVersion(context.getCheckpointVersion())
                .unsafeResumeRequired(context.isUnsafeResumeRequired())
                .pendingApprovalId(context.getPendingApprovalId())
                .build();
    }

    public AgentContext restore() {
        AgentContext context = new AgentContext();
        context.setRunId(runId);
        context.setRequestId(requestId);
        context.setConversationId(conversationId);
        context.setQuestion(question);
        context.setResolvedWorkspace(resolvedWorkspace == null ? null : Path.of(resolvedWorkspace));
        context.setWorkspace(workspace == null && resolvedWorkspace != null
                ? WorkspaceRef.local(Path.of(resolvedWorkspace), workspaceDisplayName)
                : workspace);
        context.setWorkspaceDisplayName(workspaceDisplayName);
        context.setMaxSteps(maxSteps == null ? 0 : maxSteps);
        context.setStep(step == null ? 0 : step);
        context.setParseErrors(parseErrors == null ? 0 : parseErrors);
        context.setStartedAt(startedAt);
        context.setToolSpecs(toolSpecs == null ? new ArrayList<>() : new ArrayList<>(toolSpecs));
        context.setHistory(history == null ? new ArrayList<>() : new ArrayList<>(history));
        DynamicText dynamicText = new DynamicText();
        dynamicText.replaceEntries(dynamicTextEntries == null ? List.of() : dynamicTextEntries);
        context.setDynamicText(dynamicText);
        context.setCurrentPrompt(currentPrompt);
        context.setModelOutput(modelOutput);
        context.setDecision(decision);
        context.setToolResult(toolResult);
        context.setFinalAnswer(finalAnswer);
        context.setStopReason(stopReason);
        context.setErrorCode(errorCode);
        context.setErrorMessage(errorMessage);
        context.setPlan(plan);
        context.setReplanReason(replanReason);
        context.setReplanMessage(replanMessage);
        context.setCurrentNode(currentNode);
        context.setCheckpointVersion(checkpointVersion);
        context.setUnsafeResumeRequired(Boolean.TRUE.equals(unsafeResumeRequired));
        context.setPendingApprovalId(pendingApprovalId);
        return context;
    }

}
