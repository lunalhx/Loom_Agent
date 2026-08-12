package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.tool.model.OutboundDisclosure;
import cn.lunalhx.ai.domain.tool.model.PermissionPolicySnapshot;
import cn.lunalhx.ai.domain.tool.model.WorkspaceRef;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Immutable-at-dispatch description of the authority a child may receive.
 * The request is derived from the parent context by Runtime; a delegate may
 * only narrow these values further.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DelegateRequest {
    private transient PermissionPolicySnapshot permissionPolicySnapshot;

    private static final int MAX_CHILD_STEPS = 3;
    private static final Set<String> READ_ONLY_TOOLS = Set.of("list_files", "read_file", "search");

    private String task;
    private int requestedMaxSteps;
    private String parentRunId;
    private String rootRunId;
    private String sessionId;
    private String conversationId;
    private String workspaceRoot;
    private WorkspaceRef workspace;
    private CollaborationMode modeSnapshot;
    private PlanBinding planBinding;
    private OutboundDisclosure outboundDisclosureBoundary;
    private List<String> allowedTools;
    private String parentSummary;
    private int parentDepth;
    private int maxDepth;
    private int remainingToolSteps;
    private int remainingModelAttempts;
    private long remainingTimeoutMs;
    private long remainingTokenBudget;

    /**
     * Derive the child boundary from the already-created parent context. The
     * delegate policy is deliberately read-only and is intersected with the
     * parent's effective catalog and remaining counters.
     */
    public static DelegateRequest fromParent(AgentContext context,
                                             AgentRuntimeProperties properties,
                                             OutboundDisclosure disclosureBoundary) {
        int maxDepth = positive(properties.getSubAgentMaxDepth(), 1);
        int remainingToolSteps = Math.max(0,
                context.getMaxSteps() - context.getToolSteps() - 1);
        int remainingModelAttempts = Math.max(0,
                context.getMaxAttempts() - context.getModelAttempts());
        long remainingTimeoutMs = remainingTimeout(context, properties);
        long remainingTokenBudget = remainingTokenBudget(context, properties);

        Set<String> parentTools = context.getAllowedTools() == null
                ? context.getToolSpecs().stream()
                .map(spec -> spec.getName())
                .filter(name -> name != null && !name.isBlank())
                .collect(java.util.stream.Collectors.toSet())
                : Set.copyOf(context.getAllowedTools());
        List<String> allowedTools = parentTools.stream()
                .filter(READ_ONLY_TOOLS::contains)
                .sorted()
                .toList();

        return DelegateRequest.builder()
                .requestedMaxSteps(MAX_CHILD_STEPS)
                .parentRunId(context.getRunId())
                .rootRunId(context.getRootRunId())
                .sessionId(context.getSessionId())
                .conversationId(context.getConversationId())
                .workspaceRoot(context.getResolvedWorkspace() == null
                        ? null : context.getResolvedWorkspace().toString())
                .workspace(context.getWorkspace())
                .modeSnapshot(context.getCollaborationMode())
                .planBinding(context.getPlanBinding())
                .outboundDisclosureBoundary(disclosureBoundary == null
                        ? OutboundDisclosure.NONE : disclosureBoundary)
                .allowedTools(allowedTools)
                .parentSummary(context.workingMemoryOrCreate().taskSummary())
                .parentDepth(context.getAgentDepth())
                .maxDepth(maxDepth)
                .remainingToolSteps(remainingToolSteps)
                .remainingModelAttempts(remainingModelAttempts)
                .remainingTimeoutMs(remainingTimeoutMs)
                .remainingTokenBudget(remainingTokenBudget)
                .permissionPolicySnapshot(context.getPermissionPolicySnapshot())
                .build();
    }

    /** The effective child step cap after the requested and parent limits. */
    public int getChildMaxSteps() {
        return Math.max(0, Math.min(MAX_CHILD_STEPS,
                Math.min(Math.max(0, requestedMaxSteps), remainingToolSteps)));
    }

    /** The effective child model-attempt cap after the parent limit. */
    public int getChildMaxAttempts() {
        int childSteps = getChildMaxSteps();
        if (childSteps <= 0 || remainingModelAttempts <= 0) {
            return 0;
        }
        return Math.min(remainingModelAttempts, Math.max(childSteps * 3, childSteps + 4));
    }

    private static long remainingTimeout(AgentContext context,
                                         AgentRuntimeProperties properties) {
        long total = positive(properties.getTotalTimeoutMs(), 1_800_000L);
        Instant started = context.getStartedAt();
        if (started == null) {
            return total;
        }
        long elapsed = Math.max(0L, Duration.between(started, Instant.now()).toMillis());
        return Math.max(0L, total - elapsed);
    }

    private static long remainingTokenBudget(AgentContext context,
                                             AgentRuntimeProperties properties) {
        if (properties.getBudget() == null || !Boolean.TRUE.equals(properties.getBudget().getEnabled())
                || properties.getBudget().getMaxTotalTokens() == null
                || properties.getBudget().getMaxTotalTokens() <= 0) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, properties.getBudget().getMaxTotalTokens() - context.getUsedTokens());
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static long positive(Long value, long fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
