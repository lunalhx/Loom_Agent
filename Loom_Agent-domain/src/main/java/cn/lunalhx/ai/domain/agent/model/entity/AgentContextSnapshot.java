package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRole;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;
import cn.lunalhx.ai.domain.agent.model.valobj.ReplanReason;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.model.WorkspaceRef;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentContextSnapshot {

    private static final int LARGE_TEXT_LIMIT = 4000;

    private String runId;
    private String parentRunId;
    private String rootRunId;
    private String requestId;
    private String conversationId;
    private AgentRole agentRole;
    private Integer agentDepth;
    private Integer childOrdinal;
    private String question;
    private String pathScope;
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
    private String approvedTool;
    private String approvedPolicyFingerprint;
    private Boolean approvalExpired;
    private String expiredApprovalId;
    private Boolean subAgentSpawnAllowed;
    private String traceId;
    private String currentSpanId;
    private String parentSpanId;
    private Long traceSequenceNo;
    private Long usedPromptTokens;
    private Long usedCompletionTokens;
    private Long usedTokens;
    private BigDecimal estimatedCost;
    private String budgetBlockedReason;
    private Integer reactiveCompactAttempts;
    private String currentModel;
    private String fallbackReason;
    private ContextRecoveryStage contextRecoveryStage;
    private String recoveryModelOverride;
    private String contextTranscriptArtifactId;
    private String contextBlockedReason;
    private Integer stopHookContinuationCount;
    private Integer segmentIndex;
    private Integer segmentStartStep;
    private Integer maxSegments;
    private Integer maxTotalSteps;
    private String lastActionFingerprint;
    private Integer sameActionRepeats;
    private String lastFailureFingerprint;
    private Integer sameFailureRepeats;
    private Integer noProgressRounds;
    private List<String> requestedSkills;
    private SkillCatalog availableSkillCatalog;
    private List<SkillActivation> activatedSkills;

    public static AgentContextSnapshot from(AgentContext context) {
        return AgentContextSnapshot.builder()
                .runId(context.getRunId())
                .parentRunId(context.getParentRunId())
                .rootRunId(context.getRootRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .agentRole(context.getAgentRole())
                .agentDepth(context.getAgentDepth())
                .childOrdinal(context.getChildOrdinal())
                .question(context.getQuestion())
                .pathScope(context.getPathScope())
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
                .currentPrompt(summarizeLargeText(context.getCurrentPrompt()))
                .modelOutput(summarizeLargeText(context.getModelOutput()))
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
                .approvedTool(context.getApprovedTool())
                .approvedPolicyFingerprint(context.getApprovedPolicyFingerprint())
                .approvalExpired(context.isApprovalExpired())
                .expiredApprovalId(context.getExpiredApprovalId())
                .subAgentSpawnAllowed(context.isSubAgentSpawnAllowed())
                .traceId(context.getTraceId())
                .currentSpanId(context.getCurrentSpanId())
                .parentSpanId(context.getParentSpanId())
                .traceSequenceNo(context.getTraceSequenceNo())
                .usedPromptTokens(context.getUsedPromptTokens())
                .usedCompletionTokens(context.getUsedCompletionTokens())
                .usedTokens(context.getUsedTokens())
                .estimatedCost(context.getEstimatedCost())
                .budgetBlockedReason(context.getBudgetBlockedReason())
                .reactiveCompactAttempts(context.getReactiveCompactAttempts())
                .currentModel(context.getCurrentModel())
                .fallbackReason(context.getFallbackReason())
                .contextRecoveryStage(context.getContextRecoveryStage())
                .recoveryModelOverride(context.getRecoveryModelOverride())
                .contextTranscriptArtifactId(context.getContextTranscriptArtifactId())
                .contextBlockedReason(context.getContextBlockedReason())
                .stopHookContinuationCount(context.getStopHookContinuationCount())
                .segmentIndex(context.getSegmentIndex())
                .segmentStartStep(context.getSegmentStartStep())
                .maxSegments(context.getMaxSegments())
                .maxTotalSteps(context.getMaxTotalSteps())
                .lastActionFingerprint(context.getLastActionFingerprint())
                .sameActionRepeats(context.getSameActionRepeats())
                .lastFailureFingerprint(context.getLastFailureFingerprint())
                .sameFailureRepeats(context.getSameFailureRepeats())
                .noProgressRounds(context.getNoProgressRounds())
                .requestedSkills(context.getRequestedSkills() == null ? null : new ArrayList<>(context.getRequestedSkills()))
                .availableSkillCatalog(context.getAvailableSkillCatalog())
                .activatedSkills(context.getActivatedSkills() == null ? null : new ArrayList<>(context.getActivatedSkills()))
                .build();
    }

    private static String summarizeLargeText(String text) {
        if (StringUtils.length(text) <= LARGE_TEXT_LIMIT) {
            return text;
        }
        return StringUtils.abbreviate(text, LARGE_TEXT_LIMIT)
                + "\n[checkpoint_truncated length=" + text.length()
                + " sha256=" + DigestUtils.sha256Hex(text) + "]";
    }

    public AgentContext restore() {
        AgentContext context = new AgentContext();
        context.setRunId(runId);
        context.setParentRunId(parentRunId);
        context.setRootRunId(rootRunId);
        context.setRequestId(requestId);
        context.setConversationId(conversationId);
        context.setAgentRole(agentRole);
        context.setAgentDepth(agentDepth == null ? 0 : agentDepth);
        context.setChildOrdinal(childOrdinal == null ? 0 : childOrdinal);
        context.setQuestion(question);
        context.setPathScope(pathScope);
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
        context.setApprovedTool(approvedTool);
        context.setApprovedPolicyFingerprint(approvedPolicyFingerprint);
        context.setApprovalExpired(Boolean.TRUE.equals(approvalExpired));
        context.setExpiredApprovalId(expiredApprovalId);
        context.setSubAgentSpawnAllowed(Boolean.TRUE.equals(subAgentSpawnAllowed));
        context.setTraceId(traceId);
        context.setCurrentSpanId(currentSpanId);
        context.setParentSpanId(parentSpanId);
        context.setTraceSequenceNo(traceSequenceNo == null ? 0L : traceSequenceNo);
        context.setUsedPromptTokens(usedPromptTokens == null ? 0L : usedPromptTokens);
        context.setUsedCompletionTokens(usedCompletionTokens == null ? 0L : usedCompletionTokens);
        context.setUsedTokens(usedTokens == null ? 0L : usedTokens);
        context.setEstimatedCost(estimatedCost == null ? BigDecimal.ZERO : estimatedCost);
        context.setBudgetBlockedReason(budgetBlockedReason);
        context.setReactiveCompactAttempts(reactiveCompactAttempts == null ? 0 : reactiveCompactAttempts);
        context.setCurrentModel(currentModel);
        context.setFallbackReason(fallbackReason);
        context.setContextRecoveryStage(contextRecoveryStage == null ? ContextRecoveryStage.NONE : contextRecoveryStage);
        context.setRecoveryModelOverride(recoveryModelOverride);
        context.setContextTranscriptArtifactId(contextTranscriptArtifactId);
        context.setContextBlockedReason(contextBlockedReason);
        context.setStopHookContinuationCount(stopHookContinuationCount == null ? 0 : stopHookContinuationCount);
        context.setSegmentIndex(segmentIndex == null ? 0 : segmentIndex);
        context.setSegmentStartStep(segmentStartStep == null ? 0 : segmentStartStep);
        context.setMaxSegments(maxSegments == null ? 1 : maxSegments);
        context.setMaxTotalSteps(maxTotalSteps == null ? context.getMaxSteps() : maxTotalSteps);
        context.setLastActionFingerprint(lastActionFingerprint);
        context.setSameActionRepeats(sameActionRepeats == null ? 0 : sameActionRepeats);
        context.setLastFailureFingerprint(lastFailureFingerprint);
        context.setSameFailureRepeats(sameFailureRepeats == null ? 0 : sameFailureRepeats);
        context.setNoProgressRounds(noProgressRounds == null ? 0 : noProgressRounds);
        context.setRequestedSkills(requestedSkills == null ? null : new ArrayList<>(requestedSkills));
        context.setAvailableSkillCatalog(availableSkillCatalog);
        context.setActivatedSkills(activatedSkills == null ? null : new ArrayList<>(activatedSkills));
        return context;
    }

}
