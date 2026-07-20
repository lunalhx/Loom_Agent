package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.adapter.port.SkillRepository;
import cn.lunalhx.ai.domain.agent.model.entity.SkillDescriptor;
import cn.lunalhx.ai.domain.agent.model.entity.SkillSource;
import cn.lunalhx.ai.domain.common.LoomPaths;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.infrastructure.skill.SkillTools;
import cn.lunalhx.ai.infrastructure.tool.SandboxEnvPolicy;
import cn.lunalhx.ai.infrastructure.tool.WorkspacePathSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PathAndEnvironmentIsolationTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void workspacePathsRejectTraversalAndEscapingSymlinks() throws Exception {
        Path root = temporaryFolder.newFolder("workspace").toPath();
        Path outside = temporaryFolder.newFolder("outside").toPath();
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(root.resolve("escape"), outside);

        assertThrows(IOException.class,
                () -> WorkspacePathSanitizer.writable(root, "../outside/new.txt"));
        assertThrows(IOException.class,
                () -> WorkspacePathSanitizer.existing(root, "escape/secret.txt"));
        assertThrows(IOException.class,
                () -> WorkspacePathSanitizer.writable(root, "escape/new.txt"));
        assertEquals(root.toRealPath().resolve("nested/new.txt"),
                WorkspacePathSanitizer.writable(root, "nested/new.txt"));
    }

    @Test
    public void loomPathsNormalizeRelativeConfigAndRejectUnsafeConversationIds() throws Exception {
        Path home = temporaryFolder.newFolder("loom-home").toPath();
        Path workspace = temporaryFolder.newFolder("startup-workspace").toPath();
        LoomPaths paths = new LoomPaths(home, workspace, temporaryFolder.getRoot().toPath());

        assertEquals(workspace.resolve("config/runtime.yml"),
                paths.resolveWorkspacePath("config/../config/runtime.yml", paths.runtimeConfig()));
        assertTrue(paths.sessionTemp("conversation-1").startsWith(home));
        assertThrows(IllegalArgumentException.class, () -> paths.outputs("../escape"));
    }

    @Test
    public void systemPropertiesTakePriorityForLoomPaths() throws Exception {
        Path home = temporaryFolder.newFolder("property-home").toPath();
        Path workspace = temporaryFolder.newFolder("property-workspace").toPath();
        String previousHome = System.getProperty("loom.data-dir");
        String previousWorkspace = System.getProperty("loom.workspace-root");
        try {
            System.setProperty("loom.data-dir", home.toString());
            System.setProperty("loom.workspace-root", workspace.toString());
            LoomPaths paths = LoomPaths.system();
            assertEquals(home, paths.home());
            assertEquals(workspace, paths.startupWorkspace());
        } finally {
            restore("loom.data-dir", previousHome);
            restore("loom.workspace-root", previousWorkspace);
        }
    }

    @Test
    public void environmentPolicyFiltersMergedParentAndRequestEnvironment() {
        SandboxEnvPolicy policy = new SandboxEnvPolicy(
                SandboxEnvPolicy.Mode.BLACKLIST, Set.of(), Set.of("INTERNAL_ONLY"));
        Map<String, String> filtered = policy.filter(
                Map.of("PATH", "/bin", "API_TOKEN", "secret", "LANG", "en_US"),
                Map.of("LANG", "zh_CN", "CUSTOM_VALUE", "ok"));

        assertEquals("/bin", filtered.get("PATH"));
        assertEquals("zh_CN", filtered.get("LANG"));
        assertEquals("ok", filtered.get("CUSTOM_VALUE"));
        assertFalse(filtered.containsKey("API_TOKEN"));
        assertThrows(IllegalArgumentException.class,
                () -> policy.filter(Map.of(), Map.of("INTERNAL_ONLY", "secret")));
    }

    @Test
    public void skillResourceCopyRejectsEscapingSymlink() throws Exception {
        Path root = temporaryFolder.newFolder("skill-workspace").toPath();
        Path outside = temporaryFolder.newFolder("skill-outside").toPath();
        Files.createSymbolicLink(root.resolve("escape"), outside);
        SkillRepository repository = mock(SkillRepository.class);
        SkillDescriptor descriptor = new SkillDescriptor("demo", "demo", null, null, Map.of(), List.of(),
                false, SkillSource.PROJECT, root.resolve(".agents/skills/demo"), "hash", 1);
        when(repository.resolve("demo", root)).thenReturn(descriptor);
        when(repository.readResourceBytes(descriptor, "payload.txt")).thenReturn("payload".getBytes());
        ToolCall call = ToolCall.builder()
                .workspaceRoot(root)
                .activeSkillNames(List.of("demo"))
                .input(new ObjectMapper().readTree("{\"skill\":\"demo\",\"path\":\"payload.txt\",\"destination\":\"escape/payload.txt\"}"))
                .build();

        ToolResult result = new SkillTools.CopySkillResourceTool(repository).call(call);

        assertFalse(result.isSuccess());
        assertFalse(Files.exists(outside.resolve("payload.txt")));
    }

    private void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
