package cn.lunalhx.ai.domain.skill.service;

import cn.lunalhx.ai.domain.skill.model.ActiveSkillSnapshot;
import cn.lunalhx.ai.domain.skill.model.SkillActivationException;
import cn.lunalhx.ai.domain.skill.model.SkillCatalog;
import cn.lunalhx.ai.domain.skill.model.SkillCatalogEntry;
import cn.lunalhx.ai.domain.skill.model.SkillResourceReadException;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SkillResourceReadContractTest {

    private final SkillActivationService activation = new SkillActivationService();
    private final SkillResourceReader reader = new SkillResourceReader();

    @Test
    public void activationIndexesResourcesUnderAllowedDirectories() throws Exception {
        Path pkg = createPackageWithResource("with-ref", "references/guide.md", "Guide content.");
        ActiveSkillSnapshot snapshot = activate(pkg, "with-ref");
        assertEquals(1, snapshot.resources().size());
        assertEquals("references/guide.md", snapshot.resources().get(0).normalizedPath());
    }

    @Test
    public void readReturnsChunkForIndexedResource() throws Exception {
        Path pkg = createPackageWithResource("read-me", "references/guide.md", "Line one.\nLine two.");
        ActiveSkillSnapshot snapshot = activate(pkg, "read-me");
        SkillResourceReader.ReadResult result = reader.read(
                List.of(snapshot), "read-me", "references/guide.md", 0,
                SkillResourceReader.DEFAULT_CHUNK_BYTES);
        assertTrue(result.text());
        assertTrue(result.content().contains("Line one."));
        assertFalse(result.truncated());
    }

    @Test
    public void rejectsAbsolutePath() throws Exception {
        Path pkg = createPackageWithResource("abs-path", "references/guide.md", "Body.");
        ActiveSkillSnapshot snapshot = activate(pkg, "abs-path");
        try {
            reader.read(List.of(snapshot), "abs-path", "/etc/passwd", 0, 1024);
            fail("expected rejection");
        } catch (SkillResourceReadException e) {
            assertTrue(e.getMessage().contains("path"));
        }
    }

    @Test
    public void rejectsPathTraversal() throws Exception {
        Path pkg = createPackageWithResource("traversal", "references/guide.md", "Body.");
        ActiveSkillSnapshot snapshot = activate(pkg, "traversal");
        try {
            reader.read(List.of(snapshot), "traversal", "references/../../SKILL.md", 0, 1024);
            fail("expected rejection");
        } catch (SkillResourceReadException e) {
            assertTrue(e.getMessage().toLowerCase().contains("path")
                    || e.getMessage().contains("unindexed"));
        }
    }

    @Test
    public void rejectsSymlinkResource() throws Exception {
        Path pkg = Files.createTempDirectory("skill-symlink");
        Path references = Files.createDirectories(pkg.resolve("references"));
        Path target = Files.createTempFile("secret", ".txt");
        Files.writeString(target, "secret", StandardCharsets.UTF_8);
        Files.createSymbolicLink(references.resolve("link.md"), target);
        writeSkillMd(pkg, "symlink-skill", "Symlink skill.", "Body.");
        try {
            activate(pkg, "symlink-skill");
            fail("expected activation rejection for symlink resource");
        } catch (SkillActivationException e) {
            assertTrue(e.getMessage().contains("symlink") || e.getMessage().contains("resource"));
        }
    }

    @Test
    public void rejectsContentDrift() throws Exception {
        Path pkg = createPackageWithResource("drift", "references/guide.md", "Original.");
        ActiveSkillSnapshot snapshot = activate(pkg, "drift");
        Files.writeString(pkg.resolve("references/guide.md"), "Mutated.", StandardCharsets.UTF_8);
        try {
            reader.read(List.of(snapshot), "drift", "references/guide.md", 0, 1024);
            fail("expected drift rejection");
        } catch (SkillResourceReadException e) {
            assertTrue(e.getMessage().contains("drift"));
        }
    }

    @Test
    public void rejectsUnindexedResource() throws Exception {
        Path pkg = createPackageWithResource("unindexed", "references/guide.md", "Body.");
        ActiveSkillSnapshot snapshot = activate(pkg, "unindexed");
        try {
            reader.read(List.of(snapshot), "unindexed", "references/other.md", 0, 1024);
            fail("expected unindexed rejection");
        } catch (SkillResourceReadException e) {
            assertTrue(e.getMessage().contains("unindexed") || e.getMessage().contains("not indexed"));
        }
    }

    @Test
    public void rejectsInactiveSkill() {
        try {
            reader.read(List.of(), "missing", "references/guide.md", 0, 1024);
            fail("expected inactive skill rejection");
        } catch (SkillResourceReadException e) {
            assertTrue(e.getMessage().contains("active") || e.getMessage().contains("skill"));
        }
    }

    private ActiveSkillSnapshot activate(Path pkg, String name) throws Exception {
        byte[] skillMd = Files.readAllBytes(pkg.resolve("SKILL.md"));
        SkillCatalogEntry entry = new SkillCatalogEntry(
                name, "Desc.", "test " + name, true, true,
                digest(skillMd), null, null, null, List.of(), pkg);
        return activation.activateExplicit(
                new SkillCatalog(List.of(entry), List.of(), List.of(), List.of()),
                List.of(name)).get(0);
    }

    private static Path createPackageWithResource(String skillName, String relativePath, String content) throws Exception {
        Path pkg = Files.createTempDirectory("skill-resource");
        Path file = pkg.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        writeSkillMd(pkg, skillName, "Has resources.", "Instruction body.");
        return pkg;
    }

    private static void writeSkillMd(Path pkg, String name, String description, String body) throws Exception {
        Files.writeString(pkg.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                ---
                %s
                """.formatted(name, description, body), StandardCharsets.UTF_8);
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
