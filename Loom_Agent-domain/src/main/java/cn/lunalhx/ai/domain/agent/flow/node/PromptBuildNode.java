package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentLoopPhase;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentErrorCode;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;
import cn.lunalhx.ai.domain.agent.service.context.ContextBuildResult;
import cn.lunalhx.ai.domain.agent.service.context.ContextManager;
import cn.lunalhx.ai.domain.agent.service.context.PreparedContextView;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryInitializer;
import cn.lunalhx.ai.domain.agent.service.ledger.ControlUpdateTexts;
import cn.lunalhx.ai.domain.agent.service.prompt.LedgerPromptServices;
import cn.lunalhx.ai.domain.agent.service.workspace.WorkspaceFacts;
import cn.lunalhx.ai.domain.skill.service.SkillToolCatalogProjector;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-round prompt build: stable-prefix init, dynamic control info write,
 * context rebuild, compression metadata, and transient {@link PreparedContextView}
 * generation. Truly re-senses the field every round.
 */
public class PromptBuildNode extends AbstractAgentNode {

    private final LedgerPromptServices ledgerServices;
    private final ContextManager contextManager;
    private final ConversationHistoryAppendService ledgerAppendService;
    private final ToolRegistry toolRegistry;

    public PromptBuildNode(LedgerPromptServices ledgerServices,
                           ContextManager contextManager,
                           ConversationHistoryAppendService ledgerAppendService,
                           ToolRegistry toolRegistry) {
        super(AgentNodeNames.PROMPT_BUILD, List.of("question", "toolSpecs", "conversationHistory"));
        this.ledgerServices = Objects.requireNonNull(ledgerServices, "ledgerServices must not be null");
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager must not be null");
        this.ledgerAppendService = ledgerAppendService;
        this.toolRegistry = toolRegistry;
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        if (toolRegistry != null) {
            context.setToolSpecs(SkillToolCatalogProjector.project(context, toolRegistry));
        }
        try {
            runBootstrap(context);
        } catch (RuntimeException e) {
            fail(context, AgentStopReason.MODEL_ERROR,
                    AgentErrorCode.LEDGER_BOOTSTRAP_FAILED.code(),
                    AgentErrorCode.LEDGER_BOOTSTRAP_FAILED.defaultMessage());
            return NodeResult.fail(List.of());
        }

        appendBudgetSnapshotIfApplicable(context);

        ContextBuildResult result = context.isFloorRetryPending()
                ? contextManager.buildFloorPressed(context)
                : contextManager.build(context);

        if (result.blocked()) {
            if (!context.getActiveSkills().isEmpty()) {
                fail(context, AgentStopReason.MODEL_ERROR, "skill_context_budget_exceeded",
                        "active Skill instructions exceed the Run context budget");
                return NodeResult.fail(List.of());
            }
            context.setContextBlockedReason(result.blockedReason());
            context.waitForRecoveryInput(result.blockedReason(), null);
            AgentEvent event = AgentEvent.builder()
                    .type(AgentEventType.CONTEXT_COMPACTED)
                    .runId(context.getRunId())
                    .requestId(context.getRequestId())
                    .conversationId(context.getConversationId())
                    .workspace(context.getWorkspaceDisplayName())
                    .parentRunId(context.getParentRunId())
                    .toolSteps(context.getToolSteps())
                    .modelAttempts(context.getModelAttempts())
                    .lastTool(context.getLastTool())
                    .message("上下文超预算，需补充/拆分请求后继续")
                    .metadata(Map.of("blockedReason", StringUtils.defaultString(result.blockedReason())))
                    .build();
            return NodeResult.pauseUserInput(List.of(event));
        }

        context.setPreparedView(PreparedContextView.from(result));

        List<AgentEvent> events = new ArrayList<>();
        if (hasReduction(result)) {
            events.add(buildReductionEvent(context, result));
        }
        return NodeResult.nextNode(AgentNodeNames.MODEL_CALL, events);
    }

    private void runBootstrap(AgentContext context) {
        StablePrefix candidate = buildCandidatePrefix(context);
        this.ledgerServices.bootstrapService().bootstrap(context, candidate);
    }

    private StablePrefix buildCandidatePrefix(AgentContext context) {
        boolean isDelegate = StringUtils.isNotBlank(context.getParentRunId());
        boolean delegateAllowed = StringUtils.isBlank(context.getParentRunId());
        String pathScope = context.getPathScope();
        if (StringUtils.isBlank(pathScope)) {
            pathScope = null;
        }
        boolean includeWorkspaceFacts = context.getRunConfig() != null
                && context.getRunConfig().agent() != null
                && context.getRunConfig().agent().getFeatureFlags() != null
                && context.getRunConfig().agent().getFeatureFlags().stablePrefixWorkspaceFacts();
        WorkspaceFacts.Facts facts = collectFacts(context);
        context.setWorkspaceSnapshot(facts == null ? "" : facts.dynamicText());
        boolean stableIdentityOnly = includeWorkspaceFacts && facts != null;
        String workspaceFactsText = stableIdentityOnly ? facts.identityText() : "";
        String workspaceFingerprint = stableIdentityOnly ? facts.workspaceFingerprint() : null;
        return this.ledgerServices.prefixBuilder().build(
                isDelegate,
                delegateAllowed,
                pathScope,
                context.getToolSpecs(),
                workspaceFactsText,
                workspaceFingerprint,
                context.getCollaborationMode(),
                context.getPlanBinding(),
                context.getSkillCatalogSnapshot(),
                context.getActiveSkills());
    }

    /** Collect workspace facts exactly once per round; both the rendered text and
     *  the fingerprint come from the same capture. */
    private WorkspaceFacts.Facts collectFacts(AgentContext context) {
        try {
            if (context.getResolvedWorkspace() == null) {
                return null;
            }
            return WorkspaceFacts.build(context.getResolvedWorkspace(), null);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hasReduction(ContextBuildResult result) {
        ContextBuildResult.ContextRenderMetadata m = result.metadata();
        return m.reductions() != null && !m.reductions().isEmpty();
    }

    private AgentEvent buildReductionEvent(AgentContext context, ContextBuildResult result) {
        ContextBuildResult.ContextRenderMetadata m = result.metadata();
        int rawTotal = m.sectionRawChars().values().stream().mapToInt(Integer::intValue).sum();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", "render_only");
        metadata.put("promptRawChars", rawTotal);
        metadata.put("promptRenderedChars", m.totalChars());
        metadata.put("promptBudgetChars", m.totalBudgetChars());
        metadata.put("promptOverBudget", m.overBudget());
        metadata.put("sectionOrder", m.sectionOrder());
        metadata.put("sectionBudgets", m.sectionBudgetChars());
        metadata.put("sectionRawChars", m.sectionRawChars());
        metadata.put("sectionRenderedChars", m.sectionRenderedChars());
        metadata.put("budgetReductions", m.reductionLog());
        metadata.put("historyMerged", m.historyMerged());
        metadata.put("historySummarized", m.historySummarized());
        metadata.put("historyDeduped", m.historyDeduped());
        metadata.put("summaryReuseCount", m.summaryReuseCount());
        metadata.put("relevantMemorySelected", m.relevantMemorySelected());
        metadata.put("currentRequestChars", m.currentRequestChars());
        metadata.put("currentRequestPreserved", m.currentRequestPreserved());
        return AgentEvent.builder()
                .type(AgentEventType.CONTEXT_COMPACTED)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .parentRunId(context.getParentRunId())
                .toolSteps(context.getToolSteps())
                .modelAttempts(context.getModelAttempts())
                .lastTool(context.getLastTool())
                .message("Context reduced before model call")
                .metadata(metadata)
                .build();
    }

    private void appendBudgetSnapshotIfApplicable(AgentContext context) {
        if (ledgerAppendService == null) {
            return;
        }
        String text = ControlUpdateTexts.renderRoundBudget(context);
        if (text.isEmpty()) {
            return;
        }
        String eventKey = ConversationHistoryInitializer.eventKey(
                context.getRunId(), String.valueOf(context.getToolSteps() + 1), "budget");
        ledgerAppendService.appendControlUpdate(context, text, eventKey);
    }
}
