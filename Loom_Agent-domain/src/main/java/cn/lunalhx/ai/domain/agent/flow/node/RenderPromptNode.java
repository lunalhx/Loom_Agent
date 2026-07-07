package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.adapter.port.SkillRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.SkillActivation;
import cn.lunalhx.ai.domain.agent.model.entity.SkillDescriptor;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentErrorCode;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.service.context.ContextWindowManager;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerCompactionResult;
import cn.lunalhx.ai.domain.agent.service.prompt.LedgerPromptServices;
import cn.lunalhx.ai.domain.agent.service.prompt.RenderPromptResources;
import cn.lunalhx.ai.domain.agent.service.prompt.StablePrefixBuilder;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class RenderPromptNode extends AbstractAgentNode {

    private final ContextWindowManager contextWindowManager;
    private final RenderPromptResources resources;
    private final LedgerPromptServices ledgerServices;

    public RenderPromptNode(ContextWindowManager contextWindowManager,
                            RenderPromptResources resources,
                            LedgerPromptServices ledgerServices) {
        super(AgentNodeNames.RENDER_PROMPT, List.of("question", "toolSpecs", "conversationLedger",
                "step", "maxSteps", "maxTotalSteps", "segmentIndex", "maxSegments"));
        this.contextWindowManager = Objects.requireNonNull(contextWindowManager, "contextWindowManager must not be null");
        this.resources = Objects.requireNonNull(resources, "resources must not be null");
        this.ledgerServices = Objects.requireNonNull(ledgerServices, "ledgerServices must not be null");
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        try {
            runBootstrap(context);
        } catch (RuntimeException e) {
            fail(context, AgentStopReason.MODEL_ERROR,
                    AgentErrorCode.LEDGER_BOOTSTRAP_FAILED.code(),
                    AgentErrorCode.LEDGER_BOOTSTRAP_FAILED.defaultMessage());
            return NodeResult.next(AgentNodeNames.FAIL, List.of());
        }

        if (context.getMaxTotalSteps() > 0 && context.getStep() >= context.getMaxTotalSteps()) {
            fail(context, AgentStopReason.MAX_STEPS, "max_steps_total", "达到全局最大步骤数，已停止");
            return NodeResult.next(AgentNodeNames.FAIL, List.of());
        }
        if (context.getMaxSegments() > 1 && context.getSegmentIndex() >= context.getMaxSegments()) {
            fail(context, AgentStopReason.MAX_STEPS, "max_segments_exhausted", "所有分段已用完，已停止");
            return NodeResult.next(AgentNodeNames.FAIL, List.of());
        }
        if (context.getMaxSteps() > 0 && context.getStep() - context.getSegmentStartStep() >= context.getMaxSteps()) {
            fail(context, AgentStopReason.MAX_STEPS, "max_steps_segment", "当前分段步骤数已用完");
            return NodeResult.next(AgentNodeNames.FAIL, List.of());
        }

        // ---- Ledger compaction (single-track: micro + watermark) ----
        List<AgentEvent> compactEvents = new ArrayList<>();
        LedgerCompactionResult ledgerResult = compactLedgerIfNeeded(context);
        if (ledgerResult.compacted()) {
            compactEvents.add(ledgerCompactEvent(context, ledgerResult));
        }

        return NodeResult.next(AgentNodeNames.MODEL_CALL,
                compactEvents.isEmpty() ? List.of() : compactEvents);
    }

    private String readSkillContentCached(AgentContext context, SkillActivation activation) {
        if (activation.snapshotArtifactId() == null) {
            return "";
        }
        if (this.resources.artifactRepository() != null && this.resources.blobStore() != null) {
            ContextArtifact artifact = this.resources.artifactRepository()
                    .findByArtifactIdAndRootRunId(activation.snapshotArtifactId(), context.getRootRunId())
                    .orElse(null);
            if (artifact != null) {
                return this.resources.blobStore().read(artifact.getStorageUri());
            }
        }
        // Fallback to disk read for backward compatibility
        if (this.resources.skillRepository() != null && context.getAvailableSkillCatalog() != null) {
            for (SkillDescriptor sd : context.getAvailableSkillCatalog().skills()) {
                if (sd.name().equals(activation.name())) {
                    return this.resources.skillRepository().readSkillContent(sd);
                }
            }
        }
        return "";
    }

    // ================================================================
    // Ledger compaction
    // ================================================================

    /**
     * Run ledger compaction if the entry count exceeds the high watermark.
     *
     * <p>Returns a non-null result; caller checks {@code compacted()}.
     */
    private LedgerCompactionResult compactLedgerIfNeeded(AgentContext context) {
        try {
            return this.ledgerServices.compactionService().compactIfNeeded(context);
        } catch (Exception e) {
            // Bounded failure — ledger compaction failure must not interrupt the agent.
            return LedgerCompactionResult.notNeeded(
                    context.getConversationLedger() != null ? context.getConversationLedger().size() : 0,
                    context.getGeneration());
        }
    }

    private AgentEvent ledgerCompactEvent(AgentContext context, LedgerCompactionResult result) {
        return event(context, AgentEventType.CONTEXT_COMPACTED)
                .message("Ledger compacted before model call")
                .metadata(Map.of(
                        "compactionType", "ledger",
                        "generation", result.generation(),
                        "beforeEntryCount", result.beforeEntryCount(),
                        "afterEntryCount", result.afterEntryCount(),
                        "strategy", result.strategy() == null ? "" : result.strategy(),
                        "transcriptArtifactId", result.transcriptArtifactId() == null
                                ? "" : result.transcriptArtifactId(),
                        "compactionDepth", result.compactionDepth(),
                        "maxInputCompactionDepth", result.maxInputCompactionDepth(),
                        "maxAllowedCompactionDepth", result.maxAllowedCompactionDepth(),
                        "depthGuarded", result.depthGuarded()))
                .build();
    }

    // ================================================================
    // C9R: Ledger bootstrap
    // ================================================================

    /**
     * Bootstrap the conversation ledger before model call.
     *
     * <p>Builds a candidate {@link StablePrefix} from the current configuration
     * (tools, skills, role, path scope, spawn capability) and delegates to
     * {@link LedgerBootstrapService} for generation switching and initialization.
     *
     * <p>On failure, the caller routes directly to FAIL before any model call.
     */
    private void runBootstrap(AgentContext context) {
        StablePrefix candidate = buildCandidatePrefix(context);
        this.ledgerServices.bootstrapService().bootstrap(context, candidate);
    }

    /**
     * Build a {@link StablePrefix} from the current configuration.
     *
     * <p>Collects active skills contents from the skill repository (same code
     * path as {@link #readSkillContentCached}) and builds a deterministic
     * prefix whose fingerprint covers tools, skills/catalog, role, path scope,
     * and spawn capability.
     */
    private StablePrefix buildCandidatePrefix(AgentContext context) {
        // Build skill contents map from activated skills
        Map<String, String> skillContents = new java.util.HashMap<>();
        List<SkillActivation> activatedSkills = context.getActivatedSkills();
        if (activatedSkills != null) {
            for (SkillActivation activation : activatedSkills) {
                String content = readSkillContentCached(context, activation);
                if (StringUtils.isNotBlank(content)) {
                    skillContents.put(activation.name(), content);
                }
            }
        }

        String pathScope = context.getPathScope();
        if (StringUtils.isBlank(pathScope)) {
            pathScope = null;
        }

        return this.ledgerServices.prefixBuilder().build(
                context.getAgentRole(),
                context.isSubAgentSpawnAllowed(),
                pathScope,
                context.getToolSpecs(),
                context.getSkillCatalogText(),
                activatedSkills,
                skillContents);
    }

}
