package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentErrorCode;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.service.prompt.LedgerPromptServices;
import cn.lunalhx.ai.domain.agent.service.prompt.RenderPromptResources;
import cn.lunalhx.ai.domain.agent.service.prompt.StablePrefixBuilder;
import cn.lunalhx.ai.domain.agent.service.workspace.WorkspaceFacts;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Objects;

public class RenderPromptNode extends AbstractAgentNode {

    private final RenderPromptResources resources;
    private final LedgerPromptServices ledgerServices;

    public RenderPromptNode(RenderPromptResources resources, LedgerPromptServices ledgerServices) {
        super(AgentNodeNames.RENDER_PROMPT, List.of("question", "toolSpecs", "conversationHistory",
                "step", "maxSteps", "maxTotalSteps", "segmentIndex", "maxSegments"));
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

        return NodeResult.next(AgentNodeNames.MODEL_CALL, List.of());
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
        String workspaceFactsText = buildWorkspaceFacts(context);
        String workspaceFingerprint = buildWorkspaceFingerprint(context);
        return this.ledgerServices.prefixBuilder().build(
                isDelegate,
                delegateAllowed,
                pathScope,
                context.getToolSpecs(),
                workspaceFactsText,
                workspaceFingerprint);
    }

    private String buildWorkspaceFingerprint(AgentContext context) {
        try {
            if (context.getResolvedWorkspace() == null) {
                return null;
            }
            WorkspaceFacts.Facts facts = WorkspaceFacts.build(context.getResolvedWorkspace(), null);
            return facts.workspaceFingerprint();
        } catch (Exception e) {
            return null;
        }
    }

    private String buildWorkspaceFacts(AgentContext context) {
        try {
            if (context.getResolvedWorkspace() == null) {
                return "";
            }
            WorkspaceFacts.Facts facts = WorkspaceFacts.build(context.getResolvedWorkspace(), null);
            return facts.text();
        } catch (Exception e) {
            return "";
        }
    }

}
