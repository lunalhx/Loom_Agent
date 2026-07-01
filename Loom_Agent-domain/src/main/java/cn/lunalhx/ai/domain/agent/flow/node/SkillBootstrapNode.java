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
import java.util.regex.Pattern;

public class SkillBootstrapNode extends AbstractAgentNode {

    private static final Logger log = LoggerFactory.getLogger(SkillBootstrapNode.class);

    private static final int CATALOG_MAX_CHARS = 8000;
    private static final String APPROVAL_META_KIND = "skill_activation";
    private static final Pattern SKILL_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$");

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
        // 1. Check if skill feature is enabled
        if (skillRepository == null || !isSkillEnabled()) {
            return NodeResult.next(AgentNodeNames.START, List.of());
        }

        Path workspaceRoot = context.getResolvedWorkspace();
        if (workspaceRoot == null) {
            return NodeResult.next(AgentNodeNames.START, List.of());
        }

        // 2. Discover catalog (cached per run)
        if (context.getAvailableSkillCatalog() == null) {
            SkillCatalog catalog = skillRepository.discover(workspaceRoot);
            context.setAvailableSkillCatalog(catalog);
            context.setSkillCatalogText(catalog.renderCatalogText(CATALOG_MAX_CHARS));
        }

        // 3. Initialize activation state
        if (context.getActivatedSkills() == null) {
            context.setActivatedSkills(new ArrayList<>());
        }
        if (context.getApprovedSkillNames() == null) {
            context.setApprovedSkillNames(new ArrayList<>());
        }
        if (context.getRejectedSkillNames() == null) {
            context.setRejectedSkillNames(new ArrayList<>());
        }

        SkillCatalog catalog = context.getAvailableSkillCatalog();
        if (catalog == null) {
            return NodeResult.next(AgentNodeNames.START, List.of());
        }

        // 4. Resolve target skills based on requestedSkills semantics
        // null = not provided by client → activate all user skills (backward compat)
        // empty = explicitly no skills
        // non-empty = only activate those named
        List<String> requested = context.getRequestedSkills();
        List<String> targetNames;
        List<AgentEvent> activationEvents = new ArrayList<>();
        if (requested == null) {
            // Old client: auto-activate all user-level skills
            targetNames = new ArrayList<>();
            for (SkillDescriptor skill : catalog.skills()) {
                if (skill.source() == SkillSource.USER) {
                    targetNames.add(skill.name());
                }
            }
        } else {
            targetNames = new ArrayList<>(requested);
        }

        // 5. Validate skill names
        for (String name : targetNames) {
            if (!SKILL_NAME_PATTERN.matcher(name).matches()) {
                return NodeResult.terminal(List.of(skillErrorEvent(context, "invalid_skill_name",
                        "非法的 Skill 名称: " + name)));
            }
        }

        // Deduplicate keeping order
        List<String> uniqueNames = targetNames.stream().distinct().toList();

        // Check existence
        for (String name : uniqueNames) {
            if (skillRepository.resolve(name, workspaceRoot) == null) {
                return NodeResult.terminal(List.of(skillErrorEvent(context, "skill_not_found",
                        "Skill 不存在: " + name)));
            }
        }

        // 6. Immediately activate user-level skills (no approval needed)
        List<SkillDescriptor> projectSkills = new ArrayList<>();
        for (String name : uniqueNames) {
            if (isActivated(context, name)) {
                continue;
            }
            SkillDescriptor descriptor = skillRepository.resolve(name, workspaceRoot);
            if (descriptor == null) {
                continue;
            }
            if (descriptor.source() == SkillSource.USER || isSubAgent(context)) {
                // Sub-agents inherit all skills without re-approval
                activationEvents.add(activateSkill(context, descriptor, workspaceRoot));
            } else {
                projectSkills.add(descriptor);
            }
        }

        // 7. Handle project skills
        if (projectSkills.isEmpty()) {
            return NodeResult.next(AgentNodeNames.START, activationEvents);
        }

        // Filter out already approved or rejected
        List<SkillDescriptor> pendingProjectSkills = new ArrayList<>();
        for (SkillDescriptor sd : projectSkills) {
            if (context.getApprovedSkillNames().contains(sd.name())) {
                // Already approved via batch — activate now
                activationEvents.add(activateSkill(context, sd, workspaceRoot));
            } else if (!context.getRejectedSkillNames().contains(sd.name())) {
                pendingProjectSkills.add(sd);
            }
        }

        if (pendingProjectSkills.isEmpty()) {
            return NodeResult.next(AgentNodeNames.START, activationEvents);
        }

        // 8. Create single batch approval for all pending project skills
        // Include activation events so skill_activated events are sent for
        // any user/sub-agent skills that were activated inline
        NodeResult approvalResult = createBatchApproval(context, pendingProjectSkills, workspaceRoot);
        List<AgentEvent> allEvents = new ArrayList<>(activationEvents);
        allEvents.addAll(approvalResult.getEvents());
        return NodeResult.terminal(allEvents);
    }

    /**
     * Called during resume after batch project skill approval is granted.
     */
    public NodeResult completeActivation(AgentContext context) {
        if (skillRepository == null || !isSkillEnabled()) {
            return NodeResult.next(AgentNodeNames.START, List.of());
        }
        Path workspaceRoot = context.getResolvedWorkspace();
        if (workspaceRoot == null) {
            return NodeResult.next(AgentNodeNames.START, List.of());
        }

        List<String> approved = context.getApprovedSkillNames();
        if (approved == null || approved.isEmpty()) {
            return NodeResult.next(AgentNodeNames.START, List.of());
        }

        SkillCatalog catalog = context.getAvailableSkillCatalog();
        List<AgentEvent> events = new ArrayList<>();

        for (String name : approved) {
            if (isActivated(context, name)) {
                continue;
            }
            SkillDescriptor descriptor = skillRepository.resolve(name, workspaceRoot);
            if (descriptor == null) {
                log.warn("Approved skill not found during completeActivation: {}", name);
                continue;
            }
            // Re-verify manifest hash (stale check)
            String currentHash = descriptor.manifestSha256();
            if (catalog != null) {
                for (SkillDescriptor sd : catalog.skills()) {
                    if (sd.name().equals(name) && !sd.manifestSha256().equals(currentHash)) {
                        // Skill changed since approval — return stale error
                        return NodeResult.terminal(List.of(skillErrorEvent(context, "approval_stale",
                                "Skill " + name + " 内容在审批后已变化，请刷新技能列表后重新选择")));
                    }
                }
            }
            AgentEvent evt = activateSkill(context, descriptor, workspaceRoot);
            if (evt != null) {
                events.add(evt);
            }
        }

        // Clear approval state after activating
        context.setApprovedSkillNames(null);
        context.setPendingApprovalId(null);

        return NodeResult.next(AgentNodeNames.START, events);
    }

    private AgentEvent activateSkill(AgentContext context, SkillDescriptor descriptor, Path workspaceRoot) {
        // Dedup protection: same skill activates at most once per run
        if (isActivated(context, descriptor.name())) {
            return null;
        }

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

        return AgentEvent.builder()
                .type(AgentEventType.SKILL_ACTIVATED)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .tool("activate_skill")
                .metadata(Map.of(
                        "name", descriptor.name(),
                        "source", descriptor.source().name().toLowerCase(),
                        "manifestSha256", descriptor.manifestSha256()))
                .build();
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

    private NodeResult createBatchApproval(AgentContext context, List<SkillDescriptor> skills, Path workspaceRoot) {
        Instant now = Instant.now();
        String approvalId = UUID.randomUUID().toString();
        context.setPendingApprovalId(approvalId);

        List<String> skillNames = skills.stream().map(SkillDescriptor::name).toList();
        List<Map<String, String>> skillMeta = skills.stream()
                .map(sd -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("name", sd.name());
                    m.put("description", StringUtils.defaultString(sd.description()));
                    m.put("source", sd.source().name().toLowerCase());
                    m.put("manifestSha256", sd.manifestSha256());
                    return m;
                })
                .toList();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", APPROVAL_META_KIND);
        metadata.put("skills", skillMeta);

        String riskReason = skills.size() == 1
                ? "项目级 Skill 需要确认后激活: " + skillNames.get(0)
                : skills.size() + " 个项目级 Skill 需要确认后激活";

        String operationPreview = skills.size() == 1
                ? "skill=" + skillNames.get(0) + " source=project"
                : "skills=" + String.join(",", skillNames) + " source=project";

        PendingApproval approval = PendingApproval.builder()
                .approvalId(approvalId)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .resolvedWorkspace(context.getResolvedWorkspace())
                .workspace(context.getWorkspace())
                .workspaceDisplayName(context.getWorkspaceDisplayName())
                .tool("activate_skill")
                .input(Map.of("skills", skillNames))
                .permissionLevel(cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel.HIGH_RISK_CONFIRM)
                .riskReason(riskReason)
                .operationPreview(operationPreview)
                .metadata(metadata)
                .createdAt(now)
                .expiresAt(now.plusSeconds(Math.max(1L, properties.getApprovalTtlSeconds())))
                .context(context)
                .build();
        approvalStore.save(approval);

        AgentEvent event = AgentEvent.builder()
                .type(AgentEventType.HIGH_RISK_APPROVAL_REQUIRED)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .step(context.getStep() + 1)
                .tool("activate_skill")
                .input(Map.of("skills", skillNames))
                .approvalId(approvalId)
                .permissionLevel("HIGH_RISK_CONFIRM")
                .riskReason(riskReason)
                .operationPreview(operationPreview)
                .expiresAt(approval.getExpiresAt())
                .metadata(metadata)
                .build();

        return NodeResult.terminal(List.of(event));
    }

    private AgentEvent skillErrorEvent(AgentContext context, String code, String message) {
        return AgentEvent.builder()
                .type(AgentEventType.ERROR)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .code(code)
                .message(message)
                .build();
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

    private boolean isSkillEnabled() {
        AgentRuntimeProperties.SkillProperties skillProps = properties.getSkills();
        return skillProps == null || !Boolean.FALSE.equals(skillProps.getEnabled());
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
