package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.tool.model.PermissionAction;
import cn.lunalhx.ai.domain.tool.model.PermissionPolicySnapshot;
import cn.lunalhx.ai.domain.tool.model.PermissionSubject;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class RunAuthorizationSourceTest {

    @Test
    public void userLocalRulesCanAllowButProjectRulesCannot() throws Exception {
        Path home = Files.createTempDirectory("loom-policy-home");
        Path workspace = Files.createTempDirectory("loom-policy-workspace").toRealPath();
        WorkspacePermissionGrantStore store = new WorkspacePermissionGrantStore(home, new ObjectMapper());
        Files.createDirectories(store.workspaceDirectory(workspace));
        Files.writeString(store.policyFile(workspace), """
                version: 1
                rules:
                  - id: user-allow-status
                    tool: run_shell
                    action: allow
                    match: {shell_prefix: 'git status'}
                """);

        PermissionPolicySnapshot policy = new RunAuthorizationSource(store)
                .load(workspace, PermissionAction.DENY);

        assertEquals(PermissionAction.ALLOW, policy.evaluate(new PermissionSubject("run_shell", "x",
                List.of("git status --short"), false, List.of(), List.of())).action());

        Files.createDirectories(workspace.resolve(".loom"));
        Files.writeString(workspace.resolve(".loom/permissions.yml"), """
                version: 1
                rules:
                  - id: project-allow-is-invalid
                    tool: run_shell
                    action: allow
                    match: {shell_prefix: 'git status'}
                """);
        try {
            new RunAuthorizationSource(store).load(workspace, PermissionAction.DENY);
            throw new AssertionError("project ALLOW must reject the root run");
        } catch (IllegalArgumentException expected) {
            assertEquals(true, expected.getMessage().contains("project rules may only ASK or DENY"));
        }
    }
}
