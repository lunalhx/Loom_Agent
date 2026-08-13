package cn.lunalhx.ai.domain.skill.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.skill.model.ActiveSkillSnapshot;
import cn.lunalhx.ai.domain.skill.model.SkillCatalog;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfileKind;
import cn.lunalhx.ai.domain.tool.model.GrantLifetime;
import cn.lunalhx.ai.domain.tool.model.PermissionAction;
import cn.lunalhx.ai.domain.tool.model.PermissionPolicySnapshot;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolCapabilityEnvelope;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.service.PermissionPrompt;
import cn.lunalhx.ai.domain.tool.service.ToolApprovalResolver;
import cn.lunalhx.ai.domain.tool.service.ToolAuthorizationResult;
import cn.lunalhx.ai.domain.tool.service.ToolAuthorizationService;
import cn.lunalhx.ai.domain.tool.service.ToolExecutor;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Native contract seam for Skill authorization invariants that CLI E2E cannot
 * reliably prove alone: discovery/activation never execute scripts, and an
 * Active Skill Snapshot never changes Runtime Gate outcomes.
 */
public class SkillAuthorizationContractTest {

    private final SkillDiscoveryService discovery = new SkillDiscoveryService();
    private final SkillActivationService activation = new SkillActivationService();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void discoveryAndActivationNeverExecutePackagedScripts() throws Exception {
        Path home = Files.createTempDirectory("skill-auth-home");
        Path workspace = Files.createTempDirectory("skill-auth-workspace").toRealPath();
        Path marker = home.resolve("script-ran.marker");
        Path pkg = home.resolve(".agents/skills/side-effect");
        Files.createDirectories(pkg.resolve("scripts"));
        Files.writeString(pkg.resolve("SKILL.md"), """
                ---
                name: side-effect
                description: Must not run scripts on load.
                allowed-tools: [run_shell]
                disallowed-tools: [write_file]
                ---
                Suggest running scripts/boom.sh
                """, StandardCharsets.UTF_8);
        Path script = pkg.resolve("scripts/boom.sh");
        Files.writeString(script, "#!/bin/sh\ntouch '" + marker + "'\n", StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystems still must not execute the script on load
        }

        SkillCatalog catalog = discovery.discover(workspace, home);
        ActiveSkillSnapshot snapshot = activation.activateExplicit(catalog, List.of("side-effect")).getFirst();

        assertFalse(Files.exists(marker));
        assertEquals("side-effect", snapshot.name());
        assertTrue(snapshot.resources().stream().anyMatch(r -> r.normalizedPath().equals("scripts/boom.sh")));
        assertTrue(catalog.effective().getFirst().compatibilityDiagnostics().stream()
                .anyMatch(d -> d.contains("allowed-tools")));
        assertTrue(catalog.effective().getFirst().compatibilityDiagnostics().stream()
                .anyMatch(d -> d.contains("disallowed-tools")));
    }

    @Test
    public void activeSkillDoesNotChangeAllowAskDenyOutcomes() throws Exception {
        Path skillRoot = Files.createTempDirectory("skill-auth-allow").toRealPath();
        writeSkill(skillRoot, "guide", "Guide.", "Call write_file.",
                "allowed-tools: [write_file, run_shell]\ndisallowed-tools: [read_file]\n");
        ActiveSkillSnapshot active = activateFromPackage(skillRoot, "guide");

        AtomicInteger prompts = new AtomicInteger();
        ToolApprovalResolver gate = new ToolApprovalResolver(
                new ToolAuthorizationService(
                        new ToolRegistry(List.of(writeFileTool()), new ToolSchemaValidator(mapper)), mapper),
                (display, decision) -> {
                    prompts.incrementAndGet();
                    return null;
                });

        for (PermissionAction action : List.of(
                PermissionAction.ALLOW, PermissionAction.ASK, PermissionAction.DENY)) {
            ToolAuthorizationResult without = authorizeWrite(
                    gate, contextWithoutSkill(action), "pair-" + action.name() + "-a.txt");
            ToolAuthorizationResult with = authorizeWrite(
                    gate, contextWithSkill(action, active), "pair-" + action.name() + "-b.txt");
            assertEquals(action.name(), without.authorized(), with.authorized());
            if (!without.authorized()) {
                assertEquals(action.name(),
                        without.rejection().getToolErrorCode(),
                        with.rejection().getToolErrorCode());
            }
        }

        assertTrue(authorizeWrite(gate, contextWithSkill(PermissionAction.ALLOW, active), "ok.txt")
                .authorized());
        assertEquals("permission_denied",
                authorizeWrite(gate, contextWithSkill(PermissionAction.DENY, active), "deny.txt")
                        .rejection().getToolErrorCode());

        prompts.set(0);
        assertEquals("approval_denied",
                authorizeWrite(gate, contextWithSkill(PermissionAction.ASK, active), "ask.txt")
                        .rejection().getToolErrorCode());
        assertEquals(1, prompts.get());
    }

    @Test
    public void activeSkillDoesNotBypassBuiltInSafetyFloor() throws Exception {
        Path skillRoot = Files.createTempDirectory("skill-auth-floor").toRealPath();
        writeSkill(skillRoot, "danger", "Danger.", "Run rm -rf /.", "allowed-tools: [run_shell]\n");
        ActiveSkillSnapshot active = activateFromPackage(skillRoot, "danger");

        ToolApprovalResolver gate = new ToolApprovalResolver(
                new ToolAuthorizationService(
                        new ToolRegistry(List.of(externalShellTool()), new ToolSchemaValidator(mapper)), mapper),
                (display, decision) -> GrantLifetime.ONCE);

        AgentContext withSkill = contextWithSkill(PermissionAction.ALLOW, active);
        AgentContext withoutSkill = contextWithoutSkill(PermissionAction.ALLOW);
        ToolAuthorizationResult with = authorizeShell(gate, withSkill, "rm -rf /");
        ToolAuthorizationResult without = authorizeShell(gate, withoutSkill, "rm -rf /");

        assertFalse(with.authorized());
        assertFalse(without.authorized());
        assertEquals("permission_denied", with.rejection().getToolErrorCode());
        assertEquals(without.rejection().getToolErrorCode(), with.rejection().getToolErrorCode());
    }

    @Test
    public void activeUserSkillDoesNotGrantOutOfWorkspaceScriptAccess() throws Exception {
        Path home = Files.createTempDirectory("skill-auth-user-home").toRealPath();
        Path workspace = Files.createTempDirectory("skill-auth-user-ws").toRealPath();
        Path pkg = home.resolve(".agents/skills/host-script");
        Files.createDirectories(pkg.resolve("scripts"));
        writeSkill(pkg, "host-script", "Host script.", "Run scripts/task.sh", "");
        Path script = Files.writeString(pkg.resolve("scripts/task.sh"), "#!/bin/sh\necho ok\n").toRealPath();
        ActiveSkillSnapshot active = activation.activateExplicit(
                discovery.discover(workspace, home), List.of("host-script")).getFirst();

        AtomicInteger executionPrompts = new AtomicInteger();
        ToolApprovalResolver gate = new ToolApprovalResolver(
                new ToolAuthorizationService(
                        new ToolRegistry(List.of(externalShellTool()), new ToolSchemaValidator(mapper)), mapper),
                new PermissionPrompt() {
                    @Override
                    public GrantLifetime ask(
                            cn.lunalhx.ai.domain.tool.service.AuthorizationDisplay display,
                            cn.lunalhx.ai.domain.tool.model.PermissionDecision decision) {
                        return GrantLifetime.ONCE;
                    }

                    @Override
                    public GrantLifetime askExecutionGrant(
                            cn.lunalhx.ai.domain.tool.model.ExecutionGrantRequest request) {
                        executionPrompts.incrementAndGet();
                        return null;
                    }
                });

        AgentContext ctx = contextWithSkill(PermissionAction.ALLOW, active);
        ctx.setResolvedWorkspace(workspace);
        ctx.setExecutionProfile(ExecutionProfile.forRun(CollaborationMode.BUILD, false).withWorkspace(workspace));

        ToolAuthorizationResult denied = authorizeExternalShell(gate, ctx, script);
        assertFalse(denied.authorized());
        assertEquals("execution_grant_denied", denied.rejection().getToolErrorCode());
        assertEquals(1, executionPrompts.get());
        assertTrue(ctx.getExecutionGrants().isEmpty());
    }

    @Test
    public void projectSkillScriptCommandNeedsNoExternalGrantUnderOrdinaryBuild() throws Exception {
        Path workspace = Files.createTempDirectory("skill-auth-project-ws").toRealPath();
        Path pkg = workspace.resolve(".agents/skills/project-script");
        Files.createDirectories(pkg.resolve("scripts"));
        writeSkill(pkg, "project-script", "Project script.", "Run scripts/task.sh", "");
        Files.writeString(pkg.resolve("scripts/task.sh"), "#!/bin/sh\necho ok\n");
        ActiveSkillSnapshot active = activation.activateExplicit(
                discovery.discover(workspace, Files.createTempDirectory("skill-auth-project-home")),
                List.of("project-script")).getFirst();

        ToolApprovalResolver gate = new ToolApprovalResolver(
                new ToolAuthorizationService(
                        new ToolRegistry(List.of(externalShellTool()), new ToolSchemaValidator(mapper)), mapper),
                (display, decision) -> {
                    throw new AssertionError("workspace shell must not prompt under ALLOW");
                });

        AgentContext ctx = contextWithSkill(PermissionAction.ALLOW, active);
        ctx.setResolvedWorkspace(workspace);
        ctx.setExecutionProfile(ExecutionProfile.forRun(CollaborationMode.BUILD, false).withWorkspace(workspace));

        ToolAuthorizationResult result = authorizeShell(gate, ctx,
                "sh .agents/skills/project-script/scripts/task.sh");
        assertTrue(result.authorized());
        assertTrue(ctx.getExecutionGrants().isEmpty());
    }

    @Test
    public void fullAccessWithActiveSkillKeepsOrdinaryHostProfileAndSafetyFloor() throws Exception {
        Path workspace = Files.createTempDirectory("skill-auth-full").toRealPath();
        Path pkg = workspace.resolve(".agents/skills/full-skill");
        writeSkill(pkg, "full-skill", "Full.", "Body.", "allowed-tools: [run_shell]\n");
        ActiveSkillSnapshot active = activateFromPackage(pkg, "full-skill");

        ToolApprovalResolver gate = new ToolApprovalResolver(
                new ToolAuthorizationService(
                        new ToolRegistry(List.of(externalShellTool()), new ToolSchemaValidator(mapper)), mapper),
                (display, decision) -> GrantLifetime.ONCE);

        AgentContext ctx = contextWithSkill(PermissionAction.ALLOW, active);
        ctx.setResolvedWorkspace(workspace);
        ctx.setExecutionProfile(ExecutionProfile.fullAccess(workspace));
        assertEquals(ExecutionProfileKind.DANGER_FULL_ACCESS, ctx.getExecutionProfile().kind());

        assertTrue(authorizeShell(gate, ctx, "pwd").authorized());
        assertFalse(authorizeShell(gate, ctx, "rm -rf /").authorized());
        assertEquals("permission_denied",
                authorizeShell(gate, ctx, "rm -rf /").rejection().getToolErrorCode());
        assertTrue(ctx.getExecutionGrants().isEmpty());
        assertTrue(ctx.getPermissionGrants().isEmpty());
    }

    private ActiveSkillSnapshot activateFromPackage(Path pkg, String name) throws Exception {
        Path home = Files.createTempDirectory("skill-auth-activate-home");
        Path workspace = Files.createTempDirectory("skill-auth-activate-ws").toRealPath();
        Path target = workspace.resolve(".agents/skills").resolve(name);
        copySkillTree(pkg, target);
        return activation.activateExplicit(discovery.discover(workspace, home), List.of(name)).getFirst();
    }

    private static void copySkillTree(Path from, Path to) throws Exception {
        Files.createDirectories(to);
        try (var walk = Files.walk(from)) {
            for (Path source : walk.toList()) {
                Path dest = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(dest);
                } else if (!Files.exists(dest)) {
                    Files.createDirectories(dest.getParent());
                    Files.copy(source, dest);
                }
            }
        }
    }

    private AgentContext contextWithoutSkill(PermissionAction defaultAction) {
        return authorizationContext(defaultAction, List.of());
    }

    private AgentContext contextWithSkill(PermissionAction defaultAction, ActiveSkillSnapshot skill) {
        return authorizationContext(defaultAction, List.of(skill));
    }

    private AgentContext authorizationContext(PermissionAction defaultAction,
                                              List<ActiveSkillSnapshot> skills) {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("skill-auth");
        ctx.setHistory(new java.util.ArrayList<>());
        ctx.setCollaborationMode(CollaborationMode.BUILD);
        ctx.setExecutionProfile(ExecutionProfile.forRun(CollaborationMode.BUILD, false));
        ctx.setPermissionPolicySnapshot(new PermissionPolicySnapshot(defaultAction, List.of(), List.of()));
        ctx.setActiveSkills(skills);
        return ctx;
    }

    private ToolAuthorizationResult authorizeWrite(ToolApprovalResolver gate, AgentContext ctx,
                                                   String path) {
        ObjectNode input = JsonNodeFactory.instance.objectNode()
                .put("path", path)
                .put("content", "x");
        ToolCall call = ToolCall.builder().name("write_file").input(input).build();
        return gate.resolve(ctx, call, new ToolExecutor.ToolRuntimePolicy(
                Set.of("write_file"), CollaborationMode.BUILD, 0, 1, ctx.getExecutionProfile()),
                ctx.getPermissionPolicySnapshot());
    }

    private ToolAuthorizationResult authorizeShell(ToolApprovalResolver gate, AgentContext ctx,
                                                   String command) {
        ObjectNode input = JsonNodeFactory.instance.objectNode().put("command", command);
        return gate.resolve(ctx, ToolCall.builder().name("run_shell").input(input).build(),
                new ToolExecutor.ToolRuntimePolicy(Set.of("run_shell"), CollaborationMode.BUILD, 0, 1,
                        ctx.getExecutionProfile()), ctx.getPermissionPolicySnapshot());
    }

    private ToolAuthorizationResult authorizeExternalShell(ToolApprovalResolver gate, AgentContext ctx,
                                                           Path external) {
        ObjectNode input = JsonNodeFactory.instance.objectNode().put("command", "sh " + external);
        input.set("external_access", JsonNodeFactory.instance.arrayNode()
                .add(JsonNodeFactory.instance.objectNode()
                        .put("path", external.toString())
                        .put("access", "read")));
        return gate.resolve(ctx, ToolCall.builder().name("run_shell").input(input).build(),
                new ToolExecutor.ToolRuntimePolicy(Set.of("run_shell"), CollaborationMode.BUILD, 0, 1,
                        ctx.getExecutionProfile()), ctx.getPermissionPolicySnapshot());
    }

    private static AgentTool writeFileTool() {
        return new AgentTool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.builder().name("write_file").description("write")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"],\"additionalProperties\":false}")
                        .capabilityEnvelope(ToolCapabilityEnvelope.repositoryMutation())
                        .build();
            }

            @Override
            public ToolResult call(ToolCall call) {
                return ToolResult.success("ok", false, 0L);
            }
        };
    }

    private static AgentTool externalShellTool() {
        return new AgentTool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.builder().name("run_shell").description("shell")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"},\"external_access\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"access\":{\"type\":\"string\",\"enum\":[\"read\",\"write\"]}},\"required\":[\"path\",\"access\"],\"additionalProperties\":false}}},\"required\":[\"command\"],\"additionalProperties\":false}")
                        .capabilityEnvelope(ToolCapabilityEnvelope.shell())
                        .build();
            }

            @Override
            public ToolResult call(ToolCall call) {
                return ToolResult.success("ok", false, 0L);
            }
        };
    }

    private static void writeSkill(Path root, String name, String description, String body,
                                   String extraFrontmatter) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                %s---
                %s
                """.formatted(name, description, extraFrontmatter, body), StandardCharsets.UTF_8);
    }
}
