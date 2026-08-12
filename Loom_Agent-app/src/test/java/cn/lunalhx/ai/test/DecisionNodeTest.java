package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.flow.AgentLoopPhase;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.flow.node.DecisionNode;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.List;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * DecisionNode tests for the Loom XML protocol: JSON tool form, XML attribute
 * / child-tag form, final answers, bare-text final, and format retries.
 */
public class DecisionNodeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static AgentTool tool(String name, String schema) {
        return new AgentTool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.builder().name(name).description("test tool " + name).inputSchema(schema).build();
            }

            @Override
            public ToolResult call(ToolCall call) {
                return ToolResult.success("ok", false, 0L);
            }
        };
    }

    // ---- JSON <tool> form ----

    @Test
    public void jsonToolFormProceedsToToolDispatch() {
        String schema = "{\"type\":\"object\",\"properties\":{\"msg\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"msg\"],\"additionalProperties\":false}";
        AgentTool t = tool("msg_tool", schema);
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context("<tool>{\"name\":\"msg_tool\",\"args\":{\"msg\":\"hello\"}}</tool>", List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentNodeNames.TOOL_INPUT, result.getNextNode());
        assertNull(ctx.getToolResult());
        assertEquals("action", ctx.getDecision().getType());
        assertEquals("msg_tool", ctx.getDecision().getTool());
    }

    @Test
    public void planMarkerInsideToolJsonRemainsToolPayloadData() {
        String schema = "{\"type\":\"object\",\"properties\":{\"msg\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"msg\"],\"additionalProperties\":false}";
        AgentTool t = tool("msg_tool", schema);
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context(
                "<tool>{\"name\":\"msg_tool\",\"args\":{\"msg\":\"literal <plan_submission> marker\"}}</tool>",
                List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentNodeNames.TOOL_INPUT, result.getNextNode());
        assertEquals("msg_tool", ctx.getDecision().getTool());
    }

    @Test
    public void jsonToolMissingArgsDefaultsToEmptyObject() {
        AgentTool t = tool("empty_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context("<tool>{\"name\":\"empty_tool\"}</tool>", List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentNodeNames.TOOL_INPUT, result.getNextNode());
    }

    @Test
    public void malformedToolJsonReturnsFormatRetry() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context("<tool>{\"name\":\"msg_tool\",</tool>", List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentLoopPhase.NEXT_ROUND, result.getPhase());
        assertEquals(0, ctx.getToolSteps());
    }

    @Test
    public void missingToolNameReturnsFormatRetry() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context("<tool>{\"args\":{}}</tool>", List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentLoopPhase.NEXT_ROUND, result.getPhase());
        assertEquals(0, ctx.getToolSteps());
    }

    @Test
    public void nonObjectArgsReturnsFormatRetry() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context("<tool>{\"name\":\"msg_tool\",\"args\":[]}</tool>", List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentLoopPhase.NEXT_ROUND, result.getPhase());
        assertEquals(0, ctx.getToolSteps());
    }

    // ---- XML attribute / child-tag form ----

    @Test
    public void xmlWriteFileWithContentTag() {
        AgentTool t = tool("write_file",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"],\"additionalProperties\":false}");
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context(
                "<tool name=\"write_file\" path=\"a.txt\"><content>hello\nworld</content></tool>",
                List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentNodeNames.TOOL_INPUT, result.getNextNode());
        assertEquals("a.txt", ctx.getDecision().getInput().path("path").asText());
        assertEquals("hello\nworld", ctx.getDecision().getInput().path("content").asText());
    }

    @Test
    public void xmlWriteFileBodyFallback() {
        AgentTool t = tool("write_file",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"],\"additionalProperties\":false}");
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context(
                "<tool name=\"write_file\" path=\"a.txt\">\ndef f():\n    return 1\n</tool>",
                List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentNodeNames.TOOL_INPUT, result.getNextNode());
        assertTrue(ctx.getDecision().getInput().path("content").asText().contains("def f()"));
    }

    @Test
    public void xmlPatchFileWithOldNewTags() {
        AgentTool t = tool("patch_file",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"old_text\":{\"type\":\"string\"},\"new_text\":{\"type\":\"string\"}},\"required\":[\"path\",\"old_text\",\"new_text\"],\"additionalProperties\":false}");
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context(
                "<tool name=\"patch_file\" path=\"a.py\"><old_text>return -1</old_text><new_text>return mid</new_text></tool>",
                List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentNodeNames.TOOL_INPUT, result.getNextNode());
        assertEquals("return -1", ctx.getDecision().getInput().path("old_text").asText());
        assertEquals("return mid", ctx.getDecision().getInput().path("new_text").asText());
    }

    @Test
    public void xmlToolSingleQuotedAttrs() {
        AgentTool t = tool("read_file",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"],\"additionalProperties\":false}");
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context("<tool name='read_file' path='README.md'></tool>", List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentNodeNames.TOOL_INPUT, result.getNextNode());
        assertEquals("README.md", ctx.getDecision().getInput().path("path").asText());
    }

    @Test
    public void xmlToolWithoutNameReturnsFormatRetry() {
        AgentTool t = tool("read_file", "{\"type\":\"object\",\"additionalProperties\":true}");
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context("<tool path=\"README.md\"></tool>", List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentLoopPhase.NEXT_ROUND, result.getPhase());
    }

    // ---- final / bare text ----

    @Test
    public void finalTagCompletes() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context("<final>Done.</final>", List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentLoopPhase.COMPLETE, result.getPhase());
        assertEquals("Done.", ctx.getDecision().getAnswer());
    }

    @Test
    public void emptyFinalReturnsFormatRetry() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context("<final>   </final>", List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentLoopPhase.NEXT_ROUND, result.getPhase());
    }

    @Test
    public void bareTextCompletesAsFinal() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context("just an answer", List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentLoopPhase.COMPLETE, result.getPhase());
        assertEquals("just an answer", ctx.getDecision().getAnswer());
    }

    @Test
    public void emptyOutputReturnsFormatRetry() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context("", List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentLoopPhase.NEXT_ROUND, result.getPhase());
        assertEquals(0, ctx.getToolSteps());
    }

    // ---- plan submission ----

    @Test
    public void exactPlanSubmissionCompletesWithStructuredPayload() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context(
                "<plan_submission>{\"title\":\"Ship the first slice\",\"body\":\"Read the repository and implement the slice.\",\"dependencies\":[\"JDK 21\"]}</plan_submission>",
                List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentLoopPhase.COMPLETE, result.getPhase());
        assertEquals("plan_submission", ctx.getDecision().getType());
        assertNotNull(ctx.getDecision().getPlanSubmission());
        assertEquals("Ship the first slice", ctx.getDecision().getPlanSubmission().getTitle());
        assertEquals("Read the repository and implement the slice.",
                ctx.getDecision().getPlanSubmission().getBody());
        assertEquals(List.of("JDK 21"), ctx.getDecision().getPlanSubmission().getDependencies());
    }

    @Test
    public void planSubmissionWithUnknownFieldReturnsFormatRetry() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context(
                "<plan_submission>{\"title\":\"A\",\"body\":\"B\",\"dependencies\":[],\"target\":\"NEW\"}</plan_submission>",
                List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentLoopPhase.NEXT_ROUND, result.getPhase());
        assertEquals("retry", ctx.getDecision().getType());
    }

    @Test
    public void planSubmissionMarkerCannotFallBackToFinal() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context(
                "<plan_submission>{\"title\":\"A\",\"body\":\"B\",\"dependencies\":[]}</plan_submission><final>fallback</final>",
                List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentLoopPhase.NEXT_ROUND, result.getPhase());
        assertEquals("retry", ctx.getDecision().getType());
    }

    @Test
    public void closingPlanSubmissionMarkerCannotFallBackToBareText() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context("ordinary text </plan_submission>", List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentLoopPhase.NEXT_ROUND, result.getPhase());
        assertEquals("retry", ctx.getDecision().getType());
    }

    @Test
    public void planSubmissionRejectsTrailingOrDuplicateJson() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext trailing = context(
                "<plan_submission>{\"title\":\"A\",\"body\":\"B\",\"dependencies\":[]} {}</plan_submission>",
                List.of(t.spec()));
        AgentContext duplicate = context(
                "<plan_submission>{\"title\":\"A\",\"title\":\"B\",\"body\":\"C\",\"dependencies\":[]}</plan_submission>",
                List.of(t.spec()));

        assertEquals(AgentLoopPhase.NEXT_ROUND, node.apply(trailing).getPhase());
        assertEquals(AgentLoopPhase.NEXT_ROUND, node.apply(duplicate).getPhase());
    }

    // ---- skill activation ----

    @Test
    public void exactSkillActivationRoutesToHandlerWithoutToolInput() throws Exception {
        Path root = Files.createTempDirectory("decision-skill-activation");
        Path pkg = root.resolve("review-pr");
        Files.createDirectories(pkg);
        String skillMd = """
                ---
                name: review-pr
                description: Review pull requests.
                ---
                Check tests first.
                """;
        Files.writeString(pkg.resolve("SKILL.md"), skillMd, java.nio.charset.StandardCharsets.UTF_8);
        byte[] bytes = skillMd.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        cn.lunalhx.ai.domain.skill.model.SkillCatalogEntry entry =
                new cn.lunalhx.ai.domain.skill.model.SkillCatalogEntry(
                        "review-pr", "Review pull requests.", "project .agents/skills/review-pr",
                        true, true, digest, null, null, null, List.of(), pkg);
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context(
                "<skill_activation>{\"name\":\"review-pr\"}</skill_activation>",
                List.of(t.spec()));
        ctx.setSkillCatalogSnapshot(new cn.lunalhx.ai.domain.skill.model.SkillCatalog(
                List.of(entry), List.of(), List.of(), List.of()));
        ctx.setActiveSkills(List.of());

        NodeResult result = node.apply(ctx);

        assertEquals(AgentLoopPhase.NEXT_ROUND, result.getPhase());
        assertEquals("skill_activation", ctx.getDecision().getType());
        assertEquals("review-pr", ctx.getDecision().getSkillName());
        assertNull(ctx.getToolResult());
        assertEquals(0, ctx.getToolSteps());
        assertEquals(1, ctx.getActiveSkills().size());
        assertTrue(ctx.getActiveSkills().getFirst().instructionBody().contains("Check tests first."));
    }

    @Test
    public void skillActivationCannotFallBackToFinal() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context(
                "<skill_activation>{\"name\":\"review-pr\"}</skill_activation><final>fallback</final>",
                List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentLoopPhase.NEXT_ROUND, result.getPhase());
        assertEquals("retry", ctx.getDecision().getType());
    }

    @Test
    public void skillActivationCannotBeCombinedWithTool() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context(
                "<skill_activation>{\"name\":\"review-pr\"}</skill_activation><tool>{\"name\":\"msg_tool\",\"args\":{}}</tool>",
                List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentLoopPhase.NEXT_ROUND, result.getPhase());
        assertEquals("retry", ctx.getDecision().getType());
    }

    @Test
    public void malformedSkillActivationReturnsFormatRetry() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context(
                "<skill_activation>{\"name\":\"review-pr\"</skill_activation>",
                List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentLoopPhase.NEXT_ROUND, result.getPhase());
        assertEquals("retry", ctx.getDecision().getType());
    }

    @Test
    public void markerInsideToolPayloadRemainsSkillActivationData() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context(
                "<tool>{\"name\":\"msg_tool\",\"args\":{\"message\":\"<skill_activation>\"}}</tool>",
                List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentNodeNames.TOOL_INPUT, result.getNextNode());
        assertEquals("action", ctx.getDecision().getType());
    }

    // ---- plan deviation ----

    @Test
    public void exactPlanDeviationCompletesWithStructuredPayload() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context(
                "<plan_deviation>{\"conflict\":{\"kind\":\"scope\",\"summary\":\"The required API is outside the bound scope.\"},\"workspace_changes\":[{\"path\":\"src/main/java/example/Feature.java\",\"operation\":\"modified\",\"summary\":\"Added the initial implementation.\"}]}</plan_deviation>",
                List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentLoopPhase.COMPLETE, result.getPhase());
        assertEquals("plan_deviation", ctx.getDecision().getType());
        assertNotNull(ctx.getDecision().getPlanDeviation());
        assertEquals("scope", ctx.getDecision().getPlanDeviation().getConflict().getKind());
        assertEquals("The required API is outside the bound scope.",
                ctx.getDecision().getPlanDeviation().getConflict().getSummary());
        assertEquals(1, ctx.getDecision().getPlanDeviation().getWorkspaceChanges().size());
        assertEquals("src/main/java/example/Feature.java",
                ctx.getDecision().getPlanDeviation().getWorkspaceChanges().get(0).getPath());
    }

    @Test
    public void planDeviationCannotFallBackToAnotherAction() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context(
                "<plan_deviation>{\"conflict\":{\"kind\":\"scope\",\"summary\":\"scope changed\"},\"workspace_changes\":[]}</plan_deviation><final>fallback</final>",
                List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentLoopPhase.NEXT_ROUND, result.getPhase());
        assertEquals("retry", ctx.getDecision().getType());
    }

    @Test
    public void planDeviationCannotBeCombinedWithPlanSubmission() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context(
                "<plan_deviation>{\"conflict\":{\"kind\":\"scope\",\"summary\":\"scope changed\"},\"workspace_changes\":[]}</plan_deviation><plan_submission>{\"title\":\"A\",\"body\":\"B\",\"dependencies\":[]}</plan_submission>",
                List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentLoopPhase.NEXT_ROUND, result.getPhase());
        assertEquals("retry", ctx.getDecision().getType());
    }

    @Test
    public void planDeviationCannotBeCombinedWithTool() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context(
                "<plan_deviation>{\"conflict\":{\"kind\":\"scope\",\"summary\":\"scope changed\"},\"workspace_changes\":[]}</plan_deviation><tool>{\"name\":\"msg_tool\",\"args\":{}}</tool>",
                List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentLoopPhase.NEXT_ROUND, result.getPhase());
        assertEquals("retry", ctx.getDecision().getType());
    }

    @Test
    public void planDeviationRejectsMalformedJson() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context(
                "<plan_deviation>{\"conflict\":{\"kind\":\"scope\",\"summary\":\"scope changed\"},\"workspace_changes\":[}</plan_deviation>",
                List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentLoopPhase.NEXT_ROUND, result.getPhase());
        assertEquals("retry", ctx.getDecision().getType());
    }

    @Test
    public void markerInsideToolPayloadRemainsData() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context(
                "<tool>{\"name\":\"msg_tool\",\"args\":{\"message\":\"<plan_deviation>\"}}</tool>",
                List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentLoopPhase.NEXT_NODE, result.getPhase());
        assertEquals(AgentNodeNames.TOOL_INPUT, result.getNextNode());
        assertEquals("action", ctx.getDecision().getType());
    }

    @Test
    public void markerInsidePlanSubmissionPayloadRemainsData() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context(
                "<plan_submission>{\"title\":\"A\",\"body\":\"The text mentions <plan_deviation> as data.\",\"dependencies\":[]}</plan_submission>",
                List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentLoopPhase.COMPLETE, result.getPhase());
        assertEquals("plan_submission", ctx.getDecision().getType());
    }

    @Test
    public void ordinaryDeviationProseRemainsFinalAnswer() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context("Continuing would be a deviation from the agreed scope.",
                List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentLoopPhase.COMPLETE, result.getPhase());
        assertEquals("final", ctx.getDecision().getType());
        assertEquals("Continuing would be a deviation from the agreed scope.",
                ctx.getDecision().getAnswer());
    }

    @Test
    public void planDeviationRejectsUnknownFieldsAndUnsafePaths() {
        AgentTool t = tool("msg_tool", "{\"type\":\"object\",\"additionalProperties\":true}");
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext unknownField = context(
                "<plan_deviation>{\"conflict\":{\"kind\":\"scope\",\"summary\":\"scope changed\"},\"workspace_changes\":[],\"run_id\":\"fake\"}</plan_deviation>",
                List.of(t.spec()));
        AgentContext unsafePath = context(
                "<plan_deviation>{\"conflict\":{\"kind\":\"scope\",\"summary\":\"scope changed\"},\"workspace_changes\":[{\"path\":\"../outside.txt\",\"operation\":\"modified\",\"summary\":\"changed\"}]}</plan_deviation>",
                List.of(t.spec()));
        AgentContext duplicateField = context(
                "<plan_deviation>{\"conflict\":{\"kind\":\"scope\",\"kind\":\"objective\",\"summary\":\"scope changed\"},\"workspace_changes\":[]}</plan_deviation>",
                List.of(t.spec()));

        assertEquals(AgentLoopPhase.NEXT_ROUND, node.apply(unknownField).getPhase());
        assertEquals("retry", unknownField.getDecision().getType());
        assertEquals(AgentLoopPhase.NEXT_ROUND, node.apply(unsafePath).getPhase());
        assertEquals("retry", unsafePath.getDecision().getType());
        assertEquals(AgentLoopPhase.NEXT_ROUND, node.apply(duplicateField).getPhase());
        assertEquals("retry", duplicateField.getDecision().getType());
    }

    // ---- Tool visibility / validation moved to the input gate ----
    // DecisionNode only parses the protocol; unknown tools / invalid inputs
    // still produce an action decision routed to TOOL_INPUT.

    @Test
    public void unknownToolStillRoutesToToolInputForGate() {
        AgentTool t = tool("known", "{\"type\":\"object\",\"additionalProperties\":false}");
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context("<tool>{\"name\":\"unknown\",\"args\":{}}</tool>", List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentNodeNames.TOOL_INPUT, result.getNextNode());
        assertEquals("unknown", ctx.getDecision().getTool());
        assertNull(ctx.getToolResult());
    }

    @Test
    public void hiddenToolStillRoutesToToolInputForGate() {
        AgentTool t1 = tool("exposed", "{\"type\":\"object\",\"additionalProperties\":false}");
        AgentTool t2 = tool("hidden", "{\"type\":\"object\",\"additionalProperties\":false}");
        ToolRegistry registry = new ToolRegistry(List.of(t1, t2), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context("<tool>{\"name\":\"hidden\",\"args\":{}}</tool>", List.of(t1.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentNodeNames.TOOL_INPUT, result.getNextNode());
        assertEquals("hidden", ctx.getDecision().getTool());
        assertNull(ctx.getToolResult());
    }

    @Test
    public void missingRequiredFieldStillRoutesToToolInputForGate() {
        String schema = "{\"type\":\"object\",\"properties\":{\"cmd\":{\"type\":\"string\"}},\"required\":[\"cmd\"],\"additionalProperties\":false}";
        AgentTool t = tool("test_tool", schema);
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context("<tool>{\"name\":\"test_tool\",\"args\":{}}</tool>", List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentNodeNames.TOOL_INPUT, result.getNextNode());
        assertNull(ctx.getToolResult());
        assertNotNull(ctx.getDecision());
    }

    @Test
    public void wrongTypeStillRoutesToToolInputForGate() {
        String schema = "{\"type\":\"object\",\"properties\":{\"count\":{\"type\":\"integer\"}},\"required\":[\"count\"],\"additionalProperties\":false}";
        AgentTool t = tool("count_tool", schema);
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context(
                "<tool>{\"name\":\"count_tool\",\"args\":{\"count\":\"not_a_number\"}}</tool>",
                List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentNodeNames.TOOL_INPUT, result.getNextNode());
        assertNull(ctx.getToolResult());
    }

    @Test
    public void invalidInputDoesNotCountAsParseError() {
        String schema = "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"}},\"required\":[\"x\"],\"additionalProperties\":false}";
        AgentTool t = tool("int_tool", schema);
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context("<tool>{\"name\":\"int_tool\",\"args\":{}}</tool>", List.of(t.spec()));

        node.apply(ctx);

        assertEquals(0, ctx.getParseErrors());
    }

    @Test
    public void integerStringAttributeAcceptedBySchema() {
        // XML attributes are strings; the semantic validator accepts integer strings
        String schema = "{\"type\":\"object\",\"properties\":{\"start\":{\"type\":\"integer\"},\"end\":{\"type\":\"integer\"}},\"required\":[\"start\"],\"additionalProperties\":false}";
        AgentTool t = tool("range_tool", schema);
        ToolRegistry registry = new ToolRegistry(List.of(t), new ToolSchemaValidator(mapper));
        DecisionNode node = new DecisionNode(mapper, buildProps(), null, null);

        AgentContext ctx = context("<tool name=\"range_tool\" start=\"1\" end=\"80\"></tool>", List.of(t.spec()));

        NodeResult result = node.apply(ctx);

        assertEquals(AgentNodeNames.TOOL_INPUT, result.getNextNode());
    }

    // ---- Helper ----

    private AgentContext context(String modelOutput, List<ToolSpec> specs) {
        AgentContext ctx = new AgentContext();
        ctx.setRunId(UUID.randomUUID().toString());
        ctx.setModelOutput(modelOutput);
        ctx.setToolSpecs(specs);
        return ctx;
    }

    private AgentRuntimeProperties buildProps() {
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        props.setWorkspaceRoot(".");
        return props;
    }
}
