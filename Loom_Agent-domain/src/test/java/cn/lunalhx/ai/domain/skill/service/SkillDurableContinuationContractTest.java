package cn.lunalhx.ai.domain.skill.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.RootRunSecurityScope;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.skill.model.ActiveSkillSnapshot;
import cn.lunalhx.ai.domain.skill.model.SkillCatalog;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfileKind;
import cn.lunalhx.ai.domain.tool.model.FilesystemAccess;
import cn.lunalhx.ai.domain.tool.model.FrozenAuthorizationSnapshot;
import cn.lunalhx.ai.domain.tool.model.PermissionAction;
import cn.lunalhx.ai.domain.tool.model.PermissionPolicySnapshot;
import cn.lunalhx.ai.domain.tool.model.PermissionRule;
import cn.lunalhx.ai.domain.tool.model.WorkspaceRef;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Ticket 07 seam: restored Runs rehydrate frozen authorization and Skill state;
 * later host Skill file changes must not alter the restored result.
 */
public class SkillDurableContinuationContractTest {

    @Test
    public void restoreRehydratesFrozenAuthAndSkillsIgnoringLaterHostChanges() throws Exception {
        Path workspace = Files.createTempDirectory("skill-durable-ws").toRealPath();
        Path home = Files.createTempDirectory("skill-durable-home").toRealPath();
        Path skillDir = workspace.resolve(".agents/skills/review-pr");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: review-pr
                description: Review carefully.
                ---
                FROZEN_BODY
                """);

        AgentContext original = new AgentContext();
        original.setRunId("run-durable");
        original.setConversationId("conv-durable");
        original.setQuestion("task");
        original.setCollaborationMode(CollaborationMode.BUILD);
        original.setResolvedWorkspace(workspace);
        original.setWorkspace(WorkspaceRef.local(workspace, workspace.getFileName().toString()));
        original.setApprovalPolicy("ask");
        original.setSecurityScope(RootRunSecurityScope.create());
        original.setExecutionProfile(ExecutionProfile.forRun(CollaborationMode.BUILD, false)
                .withWorkspace(workspace));
        PermissionPolicySnapshot frozenPolicy = new PermissionPolicySnapshot(
                PermissionAction.ASK,
                List.of(new PermissionRule("freeze-r1", "project", "write_file",
                        PermissionRule.MatcherKind.TOOL, "write_file", PermissionAction.DENY)),
                List.of("frozen-src"));
        original.setPermissionPolicySnapshot(frozenPolicy);
        SkillCatalog catalog = new SkillDiscoveryService().discover(workspace, home);
        original.setSkillCatalogSnapshot(catalog);
        ActiveSkillSnapshot active = new SkillActivationService().activateExplicit(catalog, List.of("review-pr"))
                .getFirst();
        original.setActiveSkills(List.of(active));

        AgentContextSnapshot snapshot = AgentContextSnapshot.from(original);
        assertEquals(14, (int) snapshot.getSchemaVersion());
        assertNotNull(snapshot.getFrozenAuthorization());

        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: review-pr
                description: Review carefully.
                ---
                DRIFTED_BODY
                """);

        AgentContext restored = snapshot.restore();
        RootRunSecurityScope newScope = RootRunSecurityScope.create();
        restored.setSecurityScope(newScope);
        restored.setResolvedWorkspace(workspace);
        FrozenAuthorizationSnapshot frozen = snapshot.getFrozenAuthorization();
        restored.setPermissionPolicySnapshot(frozen.toPolicy());
        restored.setPermissionGrants(frozen.permissionGrants());
        restored.setExecutionGrants(frozen.executionGrants());
        restored.setApprovalPolicy(frozen.approvalPolicy());
        restored.setExecutionProfile(frozen.toExecutionProfile(
                workspace, newScope.homeRoot(), newScope.temporaryRoot()));
        SkillPackageRootBinder binder = new SkillPackageRootBinder();
        restored.setSkillCatalogSnapshot(binder.rebindCatalog(restored.getSkillCatalogSnapshot(), workspace, home));
        restored.setActiveSkills(binder.rebindActive(restored.getActiveSkills(), workspace, home));

        assertEquals("FROZEN_BODY", restored.getActiveSkills().getFirst().instructionBody().trim());
        assertEquals(PermissionAction.ASK, restored.getPermissionPolicySnapshot().defaultAction());
        assertEquals(1, restored.getPermissionPolicySnapshot().compiledRules().size());
        assertEquals(PermissionAction.DENY,
                restored.getPermissionPolicySnapshot().compiledRules().getFirst().action());
        assertEquals(ExecutionProfileKind.BUILD_SANDBOX, restored.getExecutionProfile().kind());
        assertEquals(FilesystemAccess.WRITE, restored.getExecutionProfile().workspaceAccess());
        assertNotEquals(original.getSecurityScope().homeRoot(), restored.getSecurityScope().homeRoot());
        assertTrue(Files.readString(skillDir.resolve("SKILL.md")).contains("DRIFTED_BODY"));
        assertNotNull(restored.getActiveSkills().getFirst().packageRoot());
        assertEquals(skillDir, restored.getActiveSkills().getFirst().packageRoot());
    }

    @Test
    public void permissionGrantsRemainReusableAfterExternalGrantRehydrate() {
        Path workspace = Path.of("/tmp/ws-grant").toAbsolutePath();
        Path external = Path.of("/tmp/external-cache").toAbsolutePath();
        ExecutionProfile bound = new ExecutionProfile(
                ExecutionProfileKind.BUILD_SANDBOX,
                workspace,
                FilesystemAccess.WRITE,
                Path.of("/tmp/old-home"),
                Path.of("/tmp/old-tmp"),
                false,
                false,
                List.of(new cn.lunalhx.ai.domain.tool.model.ExecutionGrant(
                        external, FilesystemAccess.READ,
                        cn.lunalhx.ai.domain.tool.model.GrantLifetime.WORKSPACE)),
                "seatbelt");
        cn.lunalhx.ai.domain.tool.model.PermissionGrant grant =
                cn.lunalhx.ai.domain.tool.model.PermissionGrant.issue(
                        "write_file|note.txt", bound,
                        cn.lunalhx.ai.domain.tool.model.GrantLifetime.SESSION);
        PermissionPolicySnapshot policy = new PermissionPolicySnapshot(
                PermissionAction.ASK, List.of(), List.of("src"));
        FrozenAuthorizationSnapshot frozen = FrozenAuthorizationSnapshot.capture(
                policy, bound, List.of(grant), List.of(), "ask");

        ExecutionProfile restored = frozen.toExecutionProfile(
                workspace, Path.of("/tmp/new-home"), Path.of("/tmp/new-tmp"));
        cn.lunalhx.ai.domain.tool.model.PermissionGrant restoredGrant =
                frozen.permissionGrants().getFirst();
        assertTrue(restoredGrant.matches("write_file|note.txt", restored));
    }
}
