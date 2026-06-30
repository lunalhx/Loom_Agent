package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.adapter.port.SkillRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.entity.SkillActivation;
import cn.lunalhx.ai.domain.agent.model.entity.SkillCatalog;
import cn.lunalhx.ai.domain.agent.model.entity.SkillDescriptor;
import cn.lunalhx.ai.domain.agent.model.entity.SkillSource;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.context.ContextArtifactKind;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SkillBootstrapNode extends AbstractAgentNode {

    private static final Logger log = LoggerFactory.getLogger(SkillBootstrapNode.class);

    private static final int CATALOG_MAX_CHARS = 8000;
    private static final String APPROVAL_META_SKILL_KEY = "__skill_name";

    private final SkillRepository skillRepository;
    private final ApprovalStore approvalStore;
    private final ContextArtifactRepository artifactRepository;
    private final ContextBlobStore blobStore;
    private final AgentRuntimeProperties properties;

    public SkillBootstrapNode(SkillRepository skillRepository,
                              ApprovalStore approvalStore,
                              ContextArtifactRepository artifactRepository,
                              ContextBlobStore blobStore,
                              AgentRuntimeProperties properties) {
        super(AgentNodeNames.SKILL_BOOTSTRAP, List.of("requestedSkills", "activatedSkills", "workspace"));
        this.skillRepository = skillRepository;
        this.approvalStore = approvalStore;
        this.artifactRepository = artifactRepository;
        this.blobStore = blobStore;
        this.properties = properties;
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        if (skillRepository == null) {
            return NodeResult.next(AgentNodeNames.START, List.of());
        }

        Path workspaceRoot = context.getResolvedWorkspace();
        if (workspaceRoot == null) {
            return NodeResult.next(AgentNodeNames.START, List.of());
        }

        // 1. Discover catalog (cached per run)
        if (context.getAvailableSkillCatalog() == null) {
            SkillCatalog catalog = skillRepository.discover(workspaceRoot);
            context.setAvailableSkillCatalog(catalog);
            context.setSkillCatalogText(catalog.renderCatalogText(CATALOG_MAX_CHARS));
        }

        // 2. Initialize activatedSkills if null
        if (context.getActivatedSkills() == null) {
            context.setActivatedSkills(new ArrayList<>());
        }

        // 3. Auto-activate all user-level skills
        SkillCatalog catalog = context.getAvailableSkillCatalog();
        if (catalog != null) {
            for (SkillDescriptor skill : catalog.skills()) {
                if (skill.source() == SkillSource.USER && !isActivated(context, skill.name())) {
                    activateSkill(context, skill, workspaceRoot);
                }
            }
        }

        // 4. Process explicit skill requests ($skill-name in question or requestedSkills)
        List<String> requested = context.getRequestedSkills();
        if (requested != null) {
            for (String skillName : requested) {
                if (isActivated(context, skillName)) {
                    continue;
                }
                SkillDescriptor descriptor = skillRepository.resolve(skillName, workspaceRoot);
                if (descriptor == null) {
                    log.warn("Requested skill not found: {}", skillName);
                    continue;
                }
                if (descriptor.source() == SkillSource.USER) {
                    activateSkill(context, descriptor, workspaceRoot);
                } else if (isSubAgent(context)) {
                    // Sub-agents inherit project skills without needing re-approval
                    activateSkill(context, descriptor, workspaceRoot);
                } else {
                    // Project skill — needs approval
                    return createApproval(context, descriptor, workspaceRoot);
                }
            }
        }

        return NodeResult.next(AgentNodeNames.START, List.of());
    }

    /**
     * Called during resume after project skill approval is granted.
     */
    public NodeResult completeActivation(AgentContext context) {
        if (skillRepository == null) {
            return NodeResult.next(AgentNodeNames.START, List.of());
        }
        Path workspaceRoot = context.getResolvedWorkspace();
        if (context.getRequestedSkills() != null) {
            for (String skillName : context.getRequestedSkills()) {
                if (isActivated(context, skillName)) {
                    continue;
                }
                SkillDescriptor descriptor = skillRepository.resolve(skillName, workspaceRoot);
                if (descriptor != null) {
                    activateSkill(context, descriptor, workspaceRoot);
                }
            }
        }
        return NodeResult.next(AgentNodeNames.START, List.of());
    }

    private void activateSkill(AgentContext context, SkillDescriptor descriptor, Path workspaceRoot) {
        String content = skillRepository.readSkillContent(descriptor);
        String snapshotArtifactId = persistSnapshot(context, descriptor, content);

        SkillActivation activation = new SkillActivation(
                descriptor.name(),
                descriptor.source(),
                descriptor.manifestSha256(),
                snapshotArtifactId,
                Instant.now(),
                descriptor.resourceCount());

        context.getActivatedSkills().add(activation);

        log.info("Skill activated: name={} source={} artifactId={}",
                descriptor.name(), descriptor.source(), snapshotArtifactId);
    }

    private String persistSnapshot(AgentContext context, SkillDescriptor descriptor, String content) {
        String artifactId = "skill-" + UUID.randomUUID();
        String storageUri = blobStore.write(context.getRootRunId(), artifactId, StringUtils.defaultString(content));
        ContextArtifact artifact = ContextArtifact.builder()
                .artifactId(artifactId)
                .runId(context.getRunId())
                .rootRunId(context.getRootRunId())
                .conversationId(context.getConversationId())
                .kind(ContextArtifactKind.SKILL_SNAPSHOT)
                .storageUri(storageUri)
                .preview(StringUtils.abbreviate(StringUtils.defaultString(content), 500))
                .sha256(DigestUtils.sha256Hex(StringUtils.defaultString(content)))
                .originalChars(StringUtils.length(content))
                .retainedChars(Math.min(StringUtils.length(content), 500))
                .createdAt(Instant.now())
                .build();
        artifactRepository.save(artifact);
        return artifactId;
    }

    private NodeResult createApproval(AgentContext context, SkillDescriptor descriptor, Path workspaceRoot) {
        Instant now = Instant.now();
        String approvalId = UUID.randomUUID().toString();
        context.setPendingApprovalId(approvalId);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(APPROVAL_META_SKILL_KEY, descriptor.name());

        PendingApproval approval = PendingApproval.builder()
                .approvalId(approvalId)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .resolvedWorkspace(context.getResolvedWorkspace())
                .workspace(context.getWorkspace())
                .workspaceDisplayName(context.getWorkspaceDisplayName())
                .tool("activate_skill")
                .input(Map.of("skill", descriptor.name()))
                .permissionLevel(cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel.HIGH_RISK_CONFIRM)
                .riskReason("项目级 Skill 需要确认后激活: " + descriptor.name())
                .operationPreview("skill=" + descriptor.name()
                        + " source=project description=" + StringUtils.abbreviate(descriptor.description(), 200))
                .metadata(metadata)
                .createdAt(now)
                .expiresAt(now.plusSeconds(Math.max(1L, properties.getApprovalTtlSeconds())))
                .context(context)
                .build();
        approvalStore.save(approval);

        List<AgentEvent> events = new ArrayList<>();
        events.add(AgentEvent.builder()
                .type(AgentEventType.HIGH_RISK_APPROVAL_REQUIRED)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .step(context.getStep() + 1)
                .tool("activate_skill")
                .input(Map.of("skill", descriptor.name()))
                .approvalId(approvalId)
                .permissionLevel("HIGH_RISK_CONFIRM")
                .riskReason("项目级 Skill 需要确认后激活: " + descriptor.name())
                .operationPreview("skill=" + descriptor.name() + " source=project")
                .expiresAt(approval.getExpiresAt())
                .build());

        return NodeResult.terminal(events);
    }

    private boolean isActivated(AgentContext context, String skillName) {
        List<SkillActivation> activated = context.getActivatedSkills();
        if (activated == null) {
            return false;
        }
        return activated.stream().anyMatch(a -> a.name().equals(skillName));
    }

    private boolean isSubAgent(AgentContext context) {
        return context.getAgentRole() != null || context.getAgentDepth() > 0;
    }

    // --- skill content retrieval for prompt rendering ---

    public String getSkillContent(AgentContext context, String skillName) {
        List<SkillActivation> activated = context.getActivatedSkills();
        if (activated == null) {
            return "";
        }
        SkillActivation activation = activated.stream()
                .filter(a -> a.name().equals(skillName))
                .findFirst().orElse(null);
        if (activation == null || activation.snapshotArtifactId() == null) {
            return "";
        }
        ContextArtifact artifact = artifactRepository
                .findByArtifactIdAndRootRunId(activation.snapshotArtifactId(), context.getRootRunId())
                .orElse(null);
        if (artifact == null) {
            return "";
        }
        return blobStore.read(artifact.getStorageUri());
    }
}
