package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.PermissionAction;
import cn.lunalhx.ai.domain.tool.model.PermissionPolicySnapshot;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.service.ToolApprovalResolver;
import cn.lunalhx.ai.domain.tool.service.ToolAuthorizationService;
import cn.lunalhx.ai.domain.tool.service.ToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/** Test-only adapter that reaches the executor through the public authorization seam. */
final class AuthorizedTestExecutor {
    private final ToolExecutor executor;
    private final ToolRegistry registry;
    private final ObjectMapper mapper;

    AuthorizedTestExecutor(ToolExecutor executor, ToolRegistry registry, ObjectMapper mapper) {
        this.executor = executor;
        this.registry = registry;
        this.mapper = mapper;
    }

    ToolResult execute(AgentContext context, ToolCall call) {
        if (context.getExecutionProfile() == null) {
            context.setExecutionProfile(ExecutionProfile.forRun(context.getCollaborationMode(), false));
        }
        if (context.getPermissionPolicySnapshot() == null) {
            context.setPermissionPolicySnapshot(new PermissionPolicySnapshot(PermissionAction.ALLOW, List.of(), List.of()));
        }
        var resolver = new ToolApprovalResolver(
                new ToolAuthorizationService(registry, mapper), (display, decision) -> null);
        var policy = new ToolExecutor.ToolRuntimePolicy(context.getAllowedTools() == null ? null :
                new java.util.LinkedHashSet<>(context.getAllowedTools()), context.getCollaborationMode(), 0, 1,
                context.getExecutionProfile());
        var authorized = resolver.resolve(context, call, policy, context.getPermissionPolicySnapshot());
        return authorized.authorized() ? executor.execute(context, authorized.authorizedCall()) : authorized.rejection();
    }
}
