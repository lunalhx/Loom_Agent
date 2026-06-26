package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRole;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;
import cn.lunalhx.ai.domain.agent.model.valobj.ReplanReason;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.model.WorkspaceRef;
import lombok.Data;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
    private boolean subAgentSpawnAllowed;
    private String traceId;
    private String currentSpanId;
    private String parentSpanId;
    private long traceSequenceNo;
    private long usedPromptTokens;
    private long usedCompletionTokens;
    private long usedTokens;
    private BigDecimal estimatedCost = BigDecimal.ZERO;
    private String budgetBlockedReason;
    private int reactiveCompactAttempts;
    private String currentModel;
    private String fallbackReason;
    private ContextRecoveryStage contextRecoveryStage = ContextRecoveryStage.NONE;
    private String recoveryModelOverride;
    private String contextTranscriptArtifactId;
    private String contextBlockedReason;
    private int stopHookContinuationCount;

    public long nextTraceSequenceNo() {
        traceSequenceNo++;
        return traceSequenceNo;
    }

}
