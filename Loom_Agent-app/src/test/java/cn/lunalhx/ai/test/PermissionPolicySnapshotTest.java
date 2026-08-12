package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.tool.model.PermissionAction;
import cn.lunalhx.ai.domain.tool.model.PermissionPolicySnapshot;
import cn.lunalhx.ai.domain.tool.model.PermissionRule;
import cn.lunalhx.ai.domain.tool.model.PermissionSubject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

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
}
