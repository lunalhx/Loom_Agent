package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.EffectProfile;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.PermissionAction;
import cn.lunalhx.ai.domain.tool.model.PermissionPolicySnapshot;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolCapabilityEnvelope;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.service.ToolAuthorizationService;
import cn.lunalhx.ai.domain.tool.service.ToolExecutor;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Contract tests exercise the public authorization seam, never parser internals. */
public class PlanModeBoundaryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";

    @Test
    public void planCatalogExcludesShellUntilItsNativeBackendIsAvailable() {
        ToolRegistry registry = registry(new AtomicInteger());
        List<String> names = registry.effectiveSpecs(CollaborationMode.PLAN).stream().map(ToolSpec::getName).toList();
        assertEquals(List.of("read_file"), names);
        assertFalse(names.contains("run_shell"));
    }

    @Test
    public void planRejectsMutationBeforePermissionPromptOrExecution() {
        AtomicInteger calls = new AtomicInteger();
        ToolAuthorizationService service = new ToolAuthorizationService(registry(calls), mapper,
                (display, decision) -> { throw new AssertionError("restricted call must not prompt"); });
        AgentContext context = context(CollaborationMode.PLAN);
        var result = service.authorize(context, ToolCall.builder().name("write_file")
                        .input(mapper.createObjectNode()).build(),
                new ToolExecutor.ToolRuntimePolicy(Set.of("write_file"), CollaborationMode.PLAN, 0, 1,
                        context.getExecutionProfile()), context.getPermissionPolicySnapshot());
        assertFalse(result.authorized());
        assertEquals("plan_mode_denied", result.rejection().getErrorCode());
        assertEquals(0, calls.get());
    }

    @Test
    public void buildAuthorizationMintsTheOnlyExecutorInput() {
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = registry(calls);
        AgentContext context = context(CollaborationMode.BUILD);
        ToolAuthorizationService service = new ToolAuthorizationService(registry, mapper,
                (display, decision) -> cn.lunalhx.ai.domain.tool.model.GrantLifetime.ONCE);
        var result = service.authorize(context, ToolCall.builder().name("write_file")
                        .input(mapper.createObjectNode()).build(),
                new ToolExecutor.ToolRuntimePolicy(Set.of("write_file"), CollaborationMode.BUILD, 0, 1,
                        context.getExecutionProfile()), context.getPermissionPolicySnapshot());
        assertTrue(result.authorized());
        assertTrue(new ToolExecutor(registry).execute(context, result.authorizedCall()).isSuccess());
        assertEquals(1, calls.get());
    }

    private AgentContext context(CollaborationMode mode) {
        AgentContext context = new AgentContext();
        context.setRunId("run-test");
        context.setCollaborationMode(mode);
        context.setHistory(new java.util.ArrayList<>());
        context.setExecutionProfile(ExecutionProfile.forRun(mode, false));
        context.setPermissionPolicySnapshot(new PermissionPolicySnapshot(PermissionAction.ALLOW, List.of(), List.of()));
        return context;
    }

    private ToolRegistry registry(AtomicInteger calls) {
        return new ToolRegistry(List.of(tool("read_file", ToolCapabilityEnvelope.repositoryRead(), calls),
                        tool("write_file", ToolCapabilityEnvelope.repositoryMutation(), calls),
                        tool("run_shell", ToolCapabilityEnvelope.shell(), calls)),
                new ToolSchemaValidator(mapper));
    }

    private AgentTool tool(String name, ToolCapabilityEnvelope envelope, AtomicInteger calls) {
        return new AgentTool() {
            @Override public ToolSpec spec() {
                return ToolSpec.builder().name(name).description(name).inputSchema(SCHEMA)
                        .capabilityEnvelope(envelope).build();
            }
            @Override public ToolResult call(ToolCall call) {
                calls.incrementAndGet();
                return ToolResult.success("ok", false, 0L);
            }
        };
    }
}
