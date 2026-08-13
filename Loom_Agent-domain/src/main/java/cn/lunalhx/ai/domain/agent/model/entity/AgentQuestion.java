package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunStartGuard;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.skill.model.ActiveSkillSnapshot;
import cn.lunalhx.ai.domain.skill.model.SkillCatalog;
import cn.lunalhx.ai.domain.tool.model.PermissionPolicySnapshot;
import cn.lunalhx.ai.domain.tool.model.ExecutionGrant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentQuestion {

    private String runId;
    private String sessionId;
    private String parentRunId;
    private String rootRunId;
    private String requestId;
    private String conversationId;
    private Integer agentDepth;
    private String question;
    private String pathScope;
    private String workspace;
    private Integer maxSteps;
    private Integer maxAttempts;
    private Boolean includeTrace;
    private String traceId;
    private String model;
    private String provider;
    private String runtimeIdentity;
    private List<String> allowedTools;
    private String approvalPolicy;
    private CollaborationMode collaborationMode;
    private String checkpointId;
    private String planTarget;
    private Integer planRevision;
    private Long planStateVersion;
    private PlanBinding planBinding;
    private AgentContextSnapshot seedSnapshot;
    private AgentRunStartGuard runStartGuard;
    /** Trusted parent-to-child handoff; never user-provided or persisted. */
    private transient PermissionPolicySnapshot inheritedPermissionPolicySnapshot;
    /** Trusted session-scoped mutable overlay, never model-provided or checkpointed. */
    private transient List<ExecutionGrant> inheritedSessionExecutionGrants;
    /** Launch-scoped Full Access selection; never written to a session or checkpoint. */
    private transient boolean fullAccess;
    private transient RootRunSecurityScope inheritedSecurityScope;
    /** Parent-frozen Skill Catalog for Delegate Runs; never rediscovered from disk. */
    private transient SkillCatalog inheritedSkillCatalogSnapshot;
    /** Parent Active Skill Snapshots at Delegate creation; child-local copies only. */
    private transient List<ActiveSkillSnapshot> inheritedActiveSkills;
    /** Session history loaded for seeding a new root run; never persisted on the question. */
    private transient List<ConversationHistoryEntry> seedHistoryEntries;
    /** Next sequence after {@link #seedHistoryEntries}; null means 0. */
    private transient Long seedHistoryNextSequence;

}
