package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.skill.model.ActiveSkillSnapshot;
import cn.lunalhx.ai.domain.skill.model.SkillCatalog;
import cn.lunalhx.ai.domain.skill.model.SkillCatalogEntry;
import cn.lunalhx.ai.domain.skill.model.SkillResourceEntry;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfileKind;
import cn.lunalhx.ai.domain.tool.model.FilesystemAccess;
import cn.lunalhx.ai.domain.tool.model.PermissionAction;
import cn.lunalhx.ai.domain.tool.model.PermissionPolicySnapshot;
import cn.lunalhx.ai.domain.tool.model.PermissionRule;
import cn.lunalhx.ai.domain.tool.model.ToolCapabilityEnvelope;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.model.WorkspaceRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Ticket 07 seam: v14 checkpoint must durable-freeze Skill Catalog, active
 * instruction bodies / resource identities, and a rehydratable authorization
 * snapshot without host-absolute Skill paths.
 */
public class AgentContextSnapshotV14ContractTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void v14RoundTripPersistsFrozenSkillsAndAuthWithoutAbsoluteSkillPaths() throws Exception {
        Path skillRoot = Path.of("/tmp/host-home/.agents/skills/review-pr").toAbsolutePath();
        AgentContext context = new AgentContext();
        context.setRunId("run-1");
        context.setSessionId("sess-1");
        context.setConversationId("conv-1");
        context.setQuestion("task");
        context.setCollaborationMode(CollaborationMode.BUILD);
        context.setWorkspace(WorkspaceRef.local(Path.of("/tmp/ws"), "ws"));
        context.setSkillCatalogSnapshot(new SkillCatalog(
                List.of(new SkillCatalogEntry(
                        "review-pr",
                        "Review PRs",
                        "project .agents/skills/review-pr",
                        true,
                        true,
                        "digest-skill",
                        null,
                        null,
                        null,
                        List.of(),
                        skillRoot)),
                List.of(),
                List.of(),
                List.of()));
        context.setActiveSkills(List.of(new ActiveSkillSnapshot(
                "review-pr",
                "project .agents/skills/review-pr",
                "Always check tests first.\n",
                "digest-skill",
                skillRoot,
                List.of(new SkillResourceEntry("references/guide.md", "digest-res")))));
        PermissionPolicySnapshot policy = new PermissionPolicySnapshot(
                PermissionAction.ASK,
                List.of(new PermissionRule("r1", "project", "write_file",
                        PermissionRule.MatcherKind.TOOL, "write_file", PermissionAction.ASK)),
                List.of("src-digest"));
        context.setPermissionPolicySnapshot(policy);
        context.setExecutionProfile(ExecutionProfile.forRun(CollaborationMode.BUILD, false)
                .withWorkspace(Path.of("/tmp/ws").toAbsolutePath()));
        context.setApprovalPolicy("ask");
        context.setAllowedTools(List.of("read_file"));

        AgentContextSnapshot snapshot = AgentContextSnapshot.from(context);
        assertEquals(14, (int) snapshot.getSchemaVersion());
        assertNotNull(snapshot.getSkillCatalogSnapshot());
        assertNotNull(snapshot.getActiveSkills());
        assertNotNull(snapshot.getFrozenAuthorization());

        String json = mapper.writeValueAsString(snapshot);
        assertFalse("persisted checkpoint must not contain host-absolute skill paths",
                json.contains(skillRoot.toString()));
        assertFalse(json.contains("/tmp/host-home"));
        assertTrue(json.contains("Always check tests first."));
        assertTrue(json.contains("references/guide.md"));
        assertTrue(json.contains("review-pr"));

        AgentContextSnapshot restored = mapper.readValue(json, AgentContextSnapshot.class);
        assertEquals(14, (int) restored.getSchemaVersion());
        AgentContext live = restored.restore();
        assertEquals(1, live.getSkillCatalogSnapshot().effective().size());
        assertEquals("review-pr", live.getSkillCatalogSnapshot().effective().getFirst().name());
        assertNull(live.getSkillCatalogSnapshot().effective().getFirst().packageRoot());
        assertEquals(1, live.getActiveSkills().size());
        assertEquals("Always check tests first.\n", live.getActiveSkills().getFirst().instructionBody());
        assertEquals(List.of(new SkillResourceEntry("references/guide.md", "digest-res")),
                live.getActiveSkills().getFirst().resources());
        assertNull(live.getActiveSkills().getFirst().packageRoot());
        assertNotNull(restored.getFrozenAuthorization());
        assertEquals(PermissionAction.ASK, restored.getFrozenAuthorization().defaultAction());
        assertEquals(1, restored.getFrozenAuthorization().compiledRules().size());
        assertEquals(ExecutionProfileKind.BUILD_SANDBOX, restored.getFrozenAuthorization().profileKind());
        assertEquals(FilesystemAccess.WRITE, restored.getFrozenAuthorization().workspaceAccess());
        assertEquals(List.of("read_file"), restored.getFrozenAuthorization().allowedTools());
    }

    @Test
    public void v13CheckpointShapeIsRejected() throws Exception {
        String json = """
                {
                  "schemaVersion": 13,
                  "runId": "run-1",
                  "question": "task",
                  "conversationId": "conv-1",
                  "runModeSnapshot": "BUILD",
                  "generation": 0,
                  "ledgerNextSequence": 0
                }
                """;
        AgentContextSnapshot snapshot = mapper.readValue(json, AgentContextSnapshot.class);
        try {
            snapshot.restore();
            fail("expected v13 rejection");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("incompatible schema"));
        }
    }

    @Test
    public void unrestrictedRunFreezesItsResolvedToolCatalog() {
        AgentContext context = new AgentContext();
        context.setRunId("run-1");
        context.setQuestion("task");
        context.setCollaborationMode(CollaborationMode.BUILD);
        context.setPermissionPolicySnapshot(new PermissionPolicySnapshot(
                PermissionAction.ASK, List.of(), List.of()));
        context.setExecutionProfile(ExecutionProfile.forRun(CollaborationMode.BUILD, false));
        context.setToolSpecs(List.of(
                ToolSpec.builder().name("write_file").capabilityEnvelope(ToolCapabilityEnvelope.repositoryMutation()).build(),
                ToolSpec.builder().name("read_file").capabilityEnvelope(ToolCapabilityEnvelope.repositoryRead()).build()));
        context.setSkillCatalogSnapshot(new SkillCatalog(List.of(), List.of(), List.of(), List.of()));

        AgentContextSnapshot snapshot = AgentContextSnapshot.from(context);

        assertEquals(List.of("read_file", "write_file"),
                snapshot.getFrozenAuthorization().allowedTools());
    }

    @Test
    public void fullAccessFrozenSnapshotCannotBeRestored() throws Exception {
        AgentContext context = new AgentContext();
        context.setRunId("run-fa");
        context.setConversationId("conv-fa");
        context.setQuestion("task");
        context.setCollaborationMode(CollaborationMode.BUILD);
        context.setWorkspace(WorkspaceRef.local(Path.of("/tmp/ws"), "ws"));
        context.setPermissionPolicySnapshot(new PermissionPolicySnapshot(
                PermissionAction.ALLOW, List.of(), List.of()));
        context.setExecutionProfile(ExecutionProfile.fullAccess(Path.of("/tmp/ws").toAbsolutePath()));
        context.setApprovalPolicy("auto");

        AgentContextSnapshot snapshot = AgentContextSnapshot.from(context);
        assertEquals(Boolean.TRUE, snapshot.getFullAccess());
        assertNotNull(snapshot.getFrozenAuthorization());

        JsonNode tree = mapper.valueToTree(snapshot);
        assertFalse(tree.toString().contains(System.getProperty("user.home")));

        try {
            snapshot.restore();
            fail("expected Full Access resume rejection");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().toLowerCase().contains("full access"));
        }
    }
}
