package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.ToolResult;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Execution service of the tool chain. Input governance lives in
 * {@link ToolAuthorizationService}; adapter invocation is
 * {@link ToolAdapterInvoker}; deterministic Agent-visible projection is
 * {@link ToolResultProjector}. This class only orchestrates those seams and
 * verifies the frozen run/snapshot binding.
 */
public class ToolExecutor {

    public static final int MAX_TOOL_OUTPUT = 4000;

    /** Runtime policy for one call, including the frozen collaboration mode. */
    public record ToolRuntimePolicy(Set<String> allowedTools,
                                    CollaborationMode mode,
                                    int depth,
                                    int maxDepth,
                                    ExecutionProfile executionProfile) {
        public ToolRuntimePolicy {
            mode = Objects.requireNonNull(mode, "collaboration mode must not be null");
            executionProfile = Objects.requireNonNull(executionProfile,
                    "executionProfile must not be null");
        }

        public static ToolRuntimePolicy root(Set<String> allowedTools,
                                             CollaborationMode mode) {
            return new ToolRuntimePolicy(allowedTools, mode, 0, 1,
                    ExecutionProfile.forRun(mode, false));
        }

        public static ToolRuntimePolicy delegateChild(Set<String> allowedTools,
                                                      CollaborationMode mode) {
            return new ToolRuntimePolicy(allowedTools, mode, 1, 1,
                    ExecutionProfile.forRun(mode, true));
        }
    }

    private final ToolRegistry registry;
    private final ToolAdapterInvoker adapterInvoker;
    private final ToolResultProjector resultProjector;

    public ToolExecutor(ToolRegistry registry, ToolOutputSanitizer sanitizer) {
        this.registry = registry;
        this.adapterInvoker = new ToolAdapterInvoker(registry);
        this.resultProjector = new ToolResultProjector(sanitizer, registry);
    }

    public ToolExecutor(ToolRegistry registry) {
        this(registry, null);
    }

    public ToolRegistry registry() {
        return registry;
    }

    /** Execute one accepted call after verifying its frozen run and snapshot binding. */
    public ToolResult execute(AgentContext context, AuthorizedToolCall authorized) {
        if (authorized == null || !Objects.equals(context.getRunId(), authorized.runId())
                || context.getPermissionPolicySnapshot() == null
                || !Objects.equals(context.getPermissionPolicySnapshot().snapshotDigest(), authorized.snapshotDigest())
                || !Objects.equals(context.getExecutionProfile(), authorized.baseExecutionProfile())) {
            return unauthorized(context);
        }
        Path root = authorized.executionCall().getWorkspaceRoot();
        boolean inspectWorkspace = resultProjector.requiresWorkspaceInspection(authorized.effectProfile());
        Map<String, String> before = inspectWorkspace
                ? RepositoryStateTracker.snapshot(root) : Map.of();
        try {
            ToolResult raw = adapterInvoker.invoke(authorized);
            Map<String, String> after = inspectWorkspace
                    ? RepositoryStateTracker.snapshot(root) : before;
            return resultProjector.projectWithDiff(context, authorized, raw, before, after);
        } catch (Exception e) {
            Map<String, String> after = inspectWorkspace
                    ? RepositoryStateTracker.snapshot(root) : before;
            return resultProjector.projectFailureWithDiff(context, authorized, e, before, after);
        }
    }

    private ToolResult unauthorized(AgentContext context) {
        ToolResult result = ToolResult.failure("unauthorized_tool_call",
                "error: executor requires an authorized tool call", 0L);
        result.setToolStatus("rejected");
        result.setToolErrorCode("unauthorized_tool_call");
        result.setSecurityEventType("unauthorized_tool_call");
        context.setToolResult(result);
        return result;
    }
}
