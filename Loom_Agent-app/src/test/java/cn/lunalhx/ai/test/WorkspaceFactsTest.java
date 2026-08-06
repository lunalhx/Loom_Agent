package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.service.workspace.WorkspaceFacts;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * 稳定/动态 workspace 分离：identity 进入缓存键，动态快照只进渲染上下文。
 */
public class WorkspaceFactsTest {

    @Test
    public void identityFingerprintIgnoresDynamicChurn() throws Exception {
        Path dir = Files.createTempDirectory("ws-test");
        Files.writeString(dir.resolve("README.md"), "readme v1");

        WorkspaceFacts.Facts a = WorkspaceFacts.build(dir, dir);
        Files.writeString(dir.resolve("README.md"), "readme v2 changed");
        WorkspaceFacts.Facts b = WorkspaceFacts.build(dir, dir);

        // 分支/仓库未变 → identity fingerprint 稳定
        assertEquals(a.workspaceFingerprint(), b.workspaceFingerprint());
        assertEquals(a.identityText(), b.identityText());
    }

    @Test
    public void identityTextContainsOnlyStructuralFields() throws Exception {
        Path dir = Files.createTempDirectory("ws-test2");
        WorkspaceFacts.Facts facts = WorkspaceFacts.build(dir, dir);

        String identity = facts.identityText();
        assertTrue(identity.contains("cwd:"));
        assertTrue(identity.contains("repo_root:"));
        assertTrue(identity.contains("branch:"));
        assertTrue(identity.contains("default_branch:"));
        assertFalse(identity.contains("status:"));
        assertFalse(identity.contains("recent_commits:"));
        assertFalse(identity.contains("project_docs:"));
    }

    @Test
    public void dynamicTextCarriesStatusAndDocs() throws Exception {
        Path dir = Files.createTempDirectory("ws-test3");
        Files.writeString(dir.resolve("README.md"), "doc-content");
        WorkspaceFacts.Facts facts = WorkspaceFacts.build(dir, dir);

        String dynamic = facts.dynamicText();
        assertTrue(dynamic.contains("status:"));
        assertTrue(dynamic.contains("recent_commits:"));
        assertTrue(dynamic.contains("project_docs:"));
    }

    @Test
    public void fullTextCombinesIdentityAndDynamic() throws Exception {
        Path dir = Files.createTempDirectory("ws-test4");
        WorkspaceFacts.Facts facts = WorkspaceFacts.build(dir, dir);
        String text = facts.text();
        assertTrue(text.startsWith("Workspace:"));
        assertTrue(text.contains("status:"));
    }

    @Test
    public void factsFingerprintExcludesDocsContent() throws Exception {
        Path dir = Files.createTempDirectory("ws-test5");
        Files.writeString(dir.resolve("AGENTS.md"), "rules v1");
        WorkspaceFacts.Facts a = WorkspaceFacts.build(dir, dir);
        Files.writeString(dir.resolve("AGENTS.md"), "rules v2");
        WorkspaceFacts.Facts b = WorkspaceFacts.build(dir, dir);
        assertEquals(a.workspaceFingerprint(), b.workspaceFingerprint());
        assertEquals(a.identityText(), b.identityText());
        assertNotEquals(a.dynamicText(), b.dynamicText());
    }
}
