package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.tool.model.PermissionAction;
import cn.lunalhx.ai.domain.tool.model.PermissionPolicySnapshot;
import cn.lunalhx.ai.domain.tool.model.PermissionRule;
import cn.lunalhx.ai.domain.tool.model.PermissionSubject;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.service.ToolCallNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PermissionPolicySnapshotTest {

    @Test
    public void denyWinsOverAskAndAllowForEachShellUnit() {
        PermissionPolicySnapshot policy = new PermissionPolicySnapshot(PermissionAction.ASK, List.of(
                new PermissionRule("allow-read", "builtin", "run_shell",
                        PermissionRule.MatcherKind.SHELL_PREFIX, "rg", PermissionAction.ALLOW),
                new PermissionRule("ask-git", "user", "run_shell",
                        PermissionRule.MatcherKind.SHELL_PREFIX, "git", PermissionAction.ASK),
                new PermissionRule("deny-rm", "builtin", "run_shell",
                        PermissionRule.MatcherKind.SHELL_PREFIX, "rm", PermissionAction.DENY)), List.of("a"));

        assertEquals(PermissionAction.DENY, policy.evaluate(new PermissionSubject("run_shell", "key",
                List.of("rg needle", "rm -rf work"), false, List.of(), List.of())).action());
    }

    @Test
    public void opaqueShellCanOnlyMatchItsExactCallKey() {
        PermissionPolicySnapshot policy = new PermissionPolicySnapshot(PermissionAction.DENY, List.of(
                new PermissionRule("safe-prefix", "user", "run_shell",
                        PermissionRule.MatcherKind.SHELL_PREFIX, "echo", PermissionAction.ALLOW),
                new PermissionRule("exact", "user", "run_shell",
                        PermissionRule.MatcherKind.EXACT_CALL, "opaque-key", PermissionAction.ASK)), List.of());

        assertEquals(PermissionAction.ASK, policy.evaluate(new PermissionSubject("run_shell", "opaque-key",
                List.of(), true, List.of(), List.of())).action());
    }

    @Test
    public void compoundShellUsesDefaultForEveryUnmatchedUnit() {
        PermissionPolicySnapshot policy = new PermissionPolicySnapshot(PermissionAction.ASK, List.of(
                new PermissionRule("allow-rg", "builtin", "run_shell",
                        PermissionRule.MatcherKind.SHELL_PREFIX, "rg", PermissionAction.ALLOW)), List.of());
        assertEquals(PermissionAction.ASK, policy.evaluate(new PermissionSubject("run_shell", "key",
                List.of("rg needle", "unknown-command"), false, List.of(), List.of())).action());
    }

    @Test
    public void safetyFloorOverridesFullAccessDefaultAndMakesHostCredentialsPerCallOnly() {
        PermissionPolicySnapshot policy = new PermissionPolicySnapshot(PermissionAction.ALLOW, List.of(), List.of());

        assertEquals(PermissionAction.DENY, policy.evaluate(new PermissionSubject("run_shell", "rm-root",
                List.of("rm -rf /"), false, List.of(), List.of())).action());
        var credential = policy.evaluate(new PermissionSubject("run_shell", "credentials",
                List.of("cat ~/.ssh/id_ed25519"), false, List.of(), List.of()));
        assertEquals(PermissionAction.ASK, credential.action());
        assertTrue(credential.perCallOnly());
        assertEquals(PermissionAction.ALLOW, policy.evaluate(new PermissionSubject("run_shell", "sample",
                List.of("cat .env.example"), false, List.of(), List.of())).action());
        assertEquals(PermissionAction.DENY, policy.evaluate(new PermissionSubject("run_shell", "fork",
                List.of(":(){ :|:& };:"), true, List.of(), List.of())).action());
    }

    @Test
    public void loneBackgroundOperatorIsOpaqueInsteadOfBeingTreatedAsACompoundRead() {
        ToolCall call = ToolCall.builder().name("run_shell")
                .input(JsonNodeFactory.instance.objectNode().put("command", "rg needle & sleep 1 && pwd"))
                .build();
        assertTrue(new ToolCallNormalizer(new ObjectMapper()).normalize(call).permissionSubject().opaque());
    }
}
