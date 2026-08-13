package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.lunalhx.ai.domain.tool.model.ExecutionGrant;

/**
 * Durable Session state (schema v5). Conversation History and AgentCheckpoint
 * are stored separately; this Session keeps mode, Session Working Memory,
 * Plans, and grants — it must not copy History or checkpoint payloads.
 *
 * <p>A session spans many root runs (one per user question). Runs reference
 * the session by {@code sessionId} and never resume an old run's node
 * execution position — only task semantics and context are restored.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSession {

    public static final int CURRENT_SCHEMA_VERSION = 5;

    private String id;
    private Integer schemaVersion;
    private String workspaceRoot;
    private CollaborationMode collaborationMode;
    private Instant createdAt;
    private Instant updatedAt;

    /** Session Working Memory projected from normally completed root Runs. */
    private WorkingContextMemory workingMemory;

    /**
     * runId of the last root Run whose Working Memory Overlay was projected into
     * {@link #workingMemory}. Stored with the Session write so crash-finish cannot
     * re-project the same Run.
     */
    private String lastProjectedRunId;

    /** Path → SHA-256 of key files the session depends on. */
    @Builder.Default
    private Map<String, String> keyFiles = new LinkedHashMap<>();

    private String runtimeIdentity;

    /** Durable Plan aggregates created by root PLAN runs. */
    @Builder.Default
    private List<Plan> plans = new ArrayList<>();

    /** The selected Plan identity; controls may clear it or select another Plan. */
    private String currentPlanId;

    /** Monotonic Plan aggregate state version used by root-run CAS. */
    private long planStateVersion;

    /** Reusable external filesystem capabilities selected for this session only. */
    @Builder.Default
    private List<ExecutionGrant> executionGrants = new ArrayList<>();

    /** Pending write-ahead record; visible Plan state is unchanged until commit. */
    private PlanSubmissionTransaction pendingPlanSubmission;
}
