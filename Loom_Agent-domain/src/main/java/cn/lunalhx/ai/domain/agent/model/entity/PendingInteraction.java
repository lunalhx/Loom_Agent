package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.GrantLifetime;
import cn.lunalhx.ai.domain.tool.model.PermissionDecision;
import cn.lunalhx.ai.domain.tool.model.PermissionGrant;
import cn.lunalhx.ai.domain.tool.service.AuthorizationDisplay;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;

import java.util.List;

/**
 * Durable unanswered Tool Approval or user-input pause. Persists a redacted
 * display, subject digest, and interaction type. Tool Approval may also keep
 * salted grant material and the crash-era durable profile so a later SESSION
 * or WORKSPACE answer can reuse {@link PermissionGrant} without raw secrets.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingInteraction {

    public static final String TOOL_APPROVAL = "TOOL_APPROVAL";
    public static final String USER_INPUT = "USER_INPUT";

    private String interactionType;
    private String redactedDisplay;
    private String subjectDigest;
    private boolean perCallOnly;
    private String toolName;
    private List<String> executableUnits;
    private String workspace;
    private String grantSalt;
    private String grantSaltedCallDigest;
    private ExecutionProfile grantProfile;

    public static PendingInteraction toolApproval(AuthorizationDisplay display,
                                                  PermissionDecision decision,
                                                  String exactKey,
                                                  ExecutionProfile profile) {
        ExecutionProfile durable = durableProfile(profile);
        PermissionGrant prepared = PermissionGrant.issue(
                exactKey, durable, GrantLifetime.SESSION);
        return PendingInteraction.builder()
                .interactionType(TOOL_APPROVAL)
                .redactedDisplay(display.toolName() + " " + display.normalizedSummary())
                .subjectDigest(DigestUtils.sha256Hex(exactKey == null ? "" : exactKey))
                .perCallOnly(decision != null && decision.perCallOnly())
                .toolName(display.toolName())
                .executableUnits(display.executableUnits())
                .workspace(display.workspace())
                .grantSalt(prepared.salt())
                .grantSaltedCallDigest(prepared.saltedCallDigest())
                .grantProfile(durable)
                .build();
    }

    public static PendingInteraction userInput(String redactedDisplay, String subject) {
        String display = redactedDisplay == null ? "" : redactedDisplay;
        return PendingInteraction.builder()
                .interactionType(USER_INPUT)
                .redactedDisplay(display)
                .subjectDigest(DigestUtils.sha256Hex(subject == null ? "" : subject))
                .build();
    }

    public boolean toolApproval() {
        return TOOL_APPROVAL.equals(interactionType);
    }

    public boolean userInput() {
        return USER_INPUT.equals(interactionType);
    }

    public AuthorizationDisplay toDisplay(ExecutionProfile profile) {
        String summary = redactedDisplay;
        if (toolName != null && summary != null && summary.startsWith(toolName + " ")) {
            summary = summary.substring(toolName.length() + 1);
        }
        return new AuthorizationDisplay(
                toolName == null ? "" : toolName,
                summary == null ? "" : summary,
                executableUnits,
                workspace == null ? "" : workspace,
                profile);
    }

    public PermissionGrant toGrant(ExecutionProfile profile, GrantLifetime lifetime) {
        ExecutionProfile bound = grantProfile != null ? grantProfile : durableProfile(profile);
        return new PermissionGrant(grantSaltedCallDigest, grantSalt, bound, lifetime);
    }

    public boolean hasGrantMaterial() {
        return grantSalt != null && grantSaltedCallDigest != null;
    }

    static ExecutionProfile durableProfile(ExecutionProfile profile) {
        java.util.Objects.requireNonNull(profile, "profile");
        return new ExecutionProfile(
                profile.kind(),
                null,
                profile.workspaceAccess(),
                null,
                null,
                profile.networkAllowed(),
                profile.hostPrivateVisible(),
                profile.externalGrants(),
                profile.sandboxBackend());
    }
}
