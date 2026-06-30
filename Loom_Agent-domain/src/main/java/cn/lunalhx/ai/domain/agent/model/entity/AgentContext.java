package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRole;
import cn.lunalhx.ai.domain.agent.model.valobj.BudgetState;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;
import cn.lunalhx.ai.domain.agent.model.valobj.ReplanReason;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.model.WorkspaceRef;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-run Agent state, owned by exactly one Agent Loop.
 *
 * <p>Concurrency contract:
 * <ul>
 *   <li>A single context instance MUST only be mutated serially by one Agent Loop.</li>
 *   <li>Parent and child Agents MUST NOT share the same context instance.</li>
 *   <li>Cross-Agent shared budget is managed exclusively by {@code BudgetGuard}.</li>
 *   <li>Async consumers and persistence logic MUST only use immutable events or snapshots.</li>
 *   <li>Progress, checkpoint, and node state fields are thread-confined and carry no
 *       concurrent-write safety guarantees.</li>
 * </ul>
 */
@Data
public class AgentContext {

    private String runId;
    private String parentRunId;
    private String rootRunId;
    private String requestId;
    private String conversationId;
    private AgentRole agentRole;
    private int agentDepth;
    private int childOrdinal;
    private String question;
    private String pathScope;
    private Path resolvedWorkspace;
    private WorkspaceRef workspace;
    private String workspaceDisplayName;
    private int maxSteps;
    private int step;
    private int parseErrors;
    private Instant startedAt;
    private List<ToolSpec> toolSpecs = new ArrayList<>();
    private List<AgentStep> history = new ArrayList<>();
    private DynamicText dynamicText = new DynamicText();
    private String currentPrompt;
    @JsonIgnore
    private transient String promptRenderCacheKey;
    private String currentSystemPrompt;
    private String currentUserPrompt;
    private String memoryContext;
    @JsonIgnore
    private transient String instructionsHash;
    @JsonIgnore
    private transient List<String> selectedMemoryIds;
    @JsonIgnore
    private transient long selectedMemoryVersion;
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
    private boolean unsafeResumeRequired;
    private String pendingApprovalId;
    private String approvedTool;
    private String approvedPolicyFingerprint;
    private boolean approvalExpired;
    private String expiredApprovalId;
    private boolean subAgentSpawnAllowed;
    private String traceId;
    private String currentSpanId;
    private String parentSpanId;
    private long traceSequenceNo;
    @JsonIgnore
    private volatile BudgetState budgetState = BudgetState.EMPTY;
    private String budgetBlockedReason;
    private int reactiveCompactAttempts;
    private String currentModel;
    private String fallbackReason;
    private ContextRecoveryStage contextRecoveryStage = ContextRecoveryStage.NONE;
    private String recoveryModelOverride;
    private String contextTranscriptArtifactId;
    private String contextBlockedReason;
    private int stopHookContinuationCount;
    private int segmentIndex;
    private int segmentStartStep;
    private int maxSegments;
    private int maxTotalSteps;
    private String lastActionFingerprint;
    private int sameActionRepeats;
    private String lastFailureFingerprint;
    private int sameFailureRepeats;
    private int noProgressRounds;

    public long nextTraceSequenceNo() {
        traceSequenceNo++;
        return traceSequenceNo;
    }

    // ---- budget delegate getters (kept for JSON/checkpoint compat) ----

    public long getUsedPromptTokens() {
        return budgetState.usedPromptTokens();
    }

    public long getUsedCompletionTokens() {
        return budgetState.usedCompletionTokens();
    }

    public long getUsedTokens() {
        return budgetState.usedTokens();
    }

    public BigDecimal getEstimatedCost() {
        return budgetState.estimatedCost();
    }

    // ---- budget delegate setters ----

    public void setUsedPromptTokens(long v) {
        budgetState = new BudgetState(v, budgetState.usedCompletionTokens(),
                budgetState.usedTokens(), budgetState.estimatedCost());
    }

    public void setUsedCompletionTokens(long v) {
        budgetState = new BudgetState(budgetState.usedPromptTokens(), v,
                budgetState.usedTokens(), budgetState.estimatedCost());
    }

    public void setUsedTokens(long v) {
        budgetState = new BudgetState(budgetState.usedPromptTokens(),
                budgetState.usedCompletionTokens(), v, budgetState.estimatedCost());
    }

    public void setEstimatedCost(BigDecimal v) {
        budgetState = new BudgetState(budgetState.usedPromptTokens(),
                budgetState.usedCompletionTokens(), budgetState.usedTokens(), v);
    }

}
