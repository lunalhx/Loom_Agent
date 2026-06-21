package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentWorkspace;
import cn.lunalhx.ai.domain.agent.model.valobj.WorkspaceResolutionException;
import cn.lunalhx.ai.domain.agent.service.AgentWorkspaceResolver;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class AgentWorkspaceResolverTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shouldUseDefaultWorkspaceWhenRequestWorkspaceBlank() throws Exception {
        Path allowedRoot = temporaryFolder.newFolder("allowed").toPath();
        Path project = Files.createDirectories(allowedRoot.resolve("project-a"));

        AgentWorkspace workspace = resolver(project, allowedRoot).resolve(null);

        assertEquals(project.toRealPath(), workspace.getRoot());
        assertEquals("project-a", workspace.getDisplayName());
    }

    @Test
    public void shouldResolveRelativeWorkspaceUnderAllowedRoot() throws Exception {
        Path allowedRoot = temporaryFolder.newFolder("allowed").toPath();
        Path projectA = Files.createDirectories(allowedRoot.resolve("project-a"));
        Path projectB = Files.createDirectories(allowedRoot.resolve("project-b"));

        AgentWorkspace workspace = resolver(projectA, allowedRoot).resolve("project-b");

        assertEquals(projectB.toRealPath(), workspace.getRoot());
        assertEquals("project-b", workspace.getDisplayName());
    }

    @Test
    public void shouldRejectRelativeWorkspaceEscapingAllowedRoot() throws Exception {
        Path allowedRoot = temporaryFolder.newFolder("allowed").toPath();
        Path project = Files.createDirectories(allowedRoot.resolve("project"));
        Files.createDirectories(temporaryFolder.getRoot().toPath().resolve("outside"));

        WorkspaceResolutionException exception = assertThrows(
                WorkspaceResolutionException.class,
                () -> resolver(project, allowedRoot).resolve("../outside"));

        assertEquals("WORKSPACE_NOT_ALLOWED", exception.getCode());
    }

    @Test
    public void shouldRejectFilesystemRootWorkspace() throws Exception {
        Path allowedRoot = temporaryFolder.newFolder("allowed").toPath();
        Path project = Files.createDirectories(allowedRoot.resolve("project"));

        WorkspaceResolutionException exception = assertThrows(
                WorkspaceResolutionException.class,
                () -> resolver(project, allowedRoot).resolve("/"));

        assertEquals("WORKSPACE_NOT_ALLOWED", exception.getCode());
    }

    @Test
    public void shouldRejectAbsoluteWorkspaceOutsideAllowedRoot() throws Exception {
        Path allowedRoot = temporaryFolder.newFolder("allowed").toPath();
        Path project = Files.createDirectories(allowedRoot.resolve("project"));
        Path outside = temporaryFolder.newFolder("outside").toPath();

        WorkspaceResolutionException exception = assertThrows(
                WorkspaceResolutionException.class,
                () -> resolver(project, allowedRoot).resolve(outside.toString()));

        assertEquals("WORKSPACE_NOT_ALLOWED", exception.getCode());
    }

    @Test
    public void shouldRejectWorkspacePointingToFile() throws Exception {
        Path allowedRoot = temporaryFolder.newFolder("allowed").toPath();
        Path project = Files.createDirectories(allowedRoot.resolve("project"));
        Files.writeString(allowedRoot.resolve("not-a-dir.txt"), "x", StandardCharsets.UTF_8);

        WorkspaceResolutionException exception = assertThrows(
                WorkspaceResolutionException.class,
                () -> resolver(project, allowedRoot).resolve("not-a-dir.txt"));

        assertEquals("WORKSPACE_NOT_DIRECTORY", exception.getCode());
    }

    @Test
    public void shouldRejectMissingWorkspace() throws Exception {
        Path allowedRoot = temporaryFolder.newFolder("allowed").toPath();
        Path project = Files.createDirectories(allowedRoot.resolve("project"));

        WorkspaceResolutionException exception = assertThrows(
                WorkspaceResolutionException.class,
                () -> resolver(project, allowedRoot).resolve("missing"));

        assertEquals("WORKSPACE_NOT_FOUND", exception.getCode());
    }

    private AgentWorkspaceResolver resolver(Path defaultWorkspace, Path allowedRoot) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setWorkspaceRoot(defaultWorkspace.toString());
        properties.setAllowedWorkspaceRoots(List.of(allowedRoot.toString()));
        return new AgentWorkspaceResolver(properties);
    }

}
