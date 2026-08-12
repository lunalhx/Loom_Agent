package cn.lunalhx.ai.domain.skill.service;

import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryInitializer;
import cn.lunalhx.ai.domain.agent.service.ledger.ControlUpdateTexts;
import cn.lunalhx.ai.domain.skill.model.ActiveSkillSnapshot;
import cn.lunalhx.ai.domain.skill.model.SkillActivationException;
import cn.lunalhx.ai.domain.skill.model.SkillCatalog;
import cn.lunalhx.ai.domain.skill.service.SkillToolCatalogProjector;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Applies model-initiated Skill Activation without Tool steps or ToolResult. */
public final class SkillActivationDecisionHandler {
    private final SkillActivationService activation = new SkillActivationService();
    private final SkillContextAdmissionService contextAdmission;
    private final ConversationHistoryAppendService ledgerAppendService;

    public SkillActivationDecisionHandler(AgentRuntimeProperties properties,
                                            ConversationHistoryAppendService ledgerAppendService,
                                            ToolRegistry toolRegistry) {
        this.contextAdmission = new SkillContextAdmissionService(properties, toolRegistry);
        this.ledgerAppendService = ledgerAppendService;
    }

    public NodeResult apply(AgentContext context, AgentDecision decision) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(decision, "decision");
        String skillName = decision.getSkillName();
        if (skillName == null || skillName.isBlank()) {
            return formatRetry(context, "skill activation payload is missing a skill name");
        }
        SkillCatalog catalog = context.getSkillCatalogSnapshot();
        if (catalog == null) {
            return formatRetry(context, "skill catalog is unavailable for this Run");
        }
        try {
            for (ActiveSkillSnapshot active : context.getActiveSkills()) {
                if (active.name().equals(skillName)) {
                    appendControlNote(context, active, true);
                    context.setToolResult(null);
                    return NodeResult.nextRound(List.of());
                }
            }
            ActiveSkillSnapshot snapshot = activation.activateImplicit(catalog, skillName);
            List<ActiveSkillSnapshot> merged = activation.mergeActive(context.getActiveSkills(), snapshot);
            List<cn.lunalhx.ai.domain.tool.model.ToolSpec> candidateTools =
                    contextAdmission.assertAdmitted(context, merged);
            context.setActiveSkills(merged);
            context.setToolSpecs(candidateTools);
            appendControlNote(context, snapshot, false);
            context.setToolResult(null);
            return NodeResult.nextRound(List.of());
        } catch (SkillActivationException e) {
            return formatRetry(context, e.getMessage());
        }
    }

    private NodeResult formatRetry(AgentContext context, String problem) {
        String message = "Runtime notice: " + problem
                + ". Reply with a valid <skill_activation>{\"name\":\"skill-name\"}</skill_activation>, "
                + "<tool> call, or a non-empty <final> answer.";
        context.setToolResult(ToolResult.failure("parse_error", message, 0L));
        return NodeResult.nextRound(List.of());
    }

    private void appendControlNote(AgentContext context, ActiveSkillSnapshot snapshot, boolean alreadyActive) {
        if (ledgerAppendService == null) {
            return;
        }
        String note = ControlUpdateTexts.renderSkillActivation(
                snapshot.name(), snapshot.sourceLabel(), alreadyActive);
        String eventKey = ConversationHistoryInitializer.eventKey(
                context.getRunId(),
                String.valueOf(context.getModelAttempts()),
                "skill_activation:" + snapshot.name() + ":" + context.getToolSteps());
        ledgerAppendService.appendControlUpdate(context, note, eventKey);
    }
}
