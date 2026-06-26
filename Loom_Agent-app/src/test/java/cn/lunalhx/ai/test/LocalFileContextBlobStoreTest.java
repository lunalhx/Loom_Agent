package cn.lunalhx.ai.test;

import cn.lunalhx.ai.infrastructure.context.LocalFileContextBlobStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class LocalFileContextBlobStoreTest {

    private Path tempDir;
    private LocalFileContextBlobStore store;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("blob-store-test-");
        store = new LocalFileContextBlobStore(tempDir.toString());
    }

    @After
    public void tearDown() throws Exception {
        Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> p.toFile().delete());
    }

    // ---- write and read with logical URI ----

    @Test
    public void writeAndRead() {
        String uri = store.write("root-123", "artifact-abc", "hello world");
        assertTrue("URI should use logical prefix", uri.startsWith("loom-agent:context-artifact:"));
        assertTrue("URI should contain rootRunId", uri.contains("root-123"));
        assertTrue("URI should contain artifactId", uri.contains("artifact-abc"));

        String content = store.read(uri);
        assertEquals("hello world", content);
    }

    // ---- cross-instance read (simulates restart) ----

    @Test
    public void crossInstanceRead() {
        String uri = store.write("root-456", "artifact-def", "persisted content");

        LocalFileContextBlobStore anotherInstance = new LocalFileContextBlobStore(tempDir.toString());
        String content = anotherInstance.read(uri);
        assertEquals("persisted content", content);
    }

    // ---- backward compatibility: read old absolute path URI ----

    @Test
    public void readOldAbsolutePathUri() {
        String oldStylePath = store.write("root-789", "artifact-ghi", "old format");

        // Simulate: the old write returned an absolute filesystem path
        // We strip the logical prefix and reconstruct the absolute path
        String logicalUri = store.write("root-789", "artifact-ghi-2", "another");
        // Read with a new instance configured to same root
        LocalFileContextBlobStore another = new LocalFileContextBlobStore(tempDir.toString());

        // old format: absolute filesystem path
        Path file = tempDir.resolve("root-789").resolve("artifact-ghi.txt");
        String content = another.read(file.toString());
        assertEquals("old format", content);
    }

    // ---- path traversal: invalid rootRunId is sanitized ----

    @Test
    public void rootRunIdPathSeparatorsAreSanitized() {
        String uri = store.write("../../etc", "artifact", "data");
        assertTrue("URI should use logical prefix", uri.startsWith("loom-agent:context-artifact:"));
        assertEquals("data", store.read(uri));
    }

    // ---- path traversal: invalid artifactId is sanitized ----

    @Test
    public void artifactIdPathSeparatorsAreSanitized() {
        String uri = store.write("root", "../escape", "data");
        assertTrue("URI should not contain raw path separators", !uri.contains("../"));
        assertEquals("data", store.read(uri));
    }

    // ---- path traversal: URI outside storage root ----

    @Test
    public void readUriOutsideStorageRootThrows() {
        try {
            store.read("/etc/passwd");
            fail("Should have thrown");
        } catch (IllegalStateException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }
    }

    // ---- non-existent file returns empty ----

    @Test
    public void readNonExistentReturnsEmpty() {
        String content = store.read("loom-agent:context-artifact:no-root/no-artifact");
        assertEquals("", content);
    }

    // ---- empty content ----

    @Test
    public void writeNullContentWritesEmpty() {
        String uri = store.write("root", "artifact", null);
        String content = store.read(uri);
        assertEquals("", content);
    }

    // ---- default storage root ----

    @Test
    public void defaultStorageRootUsesTmpdir() {
        LocalFileContextBlobStore defaultStore = new LocalFileContextBlobStore(null);
        String uri = defaultStore.write("test-root", "test-artifact", "default");
        assertNotNull(uri);
        String content = defaultStore.read(uri);
        assertEquals("default", content);
    }

    // ---- special characters in segments are sanitized ----

    @Test
    public void specialCharactersAreSanitized() {
        String uri = store.write("root/../evil", "artifact<script>", "safe");
        assertFalse("URI should not contain angle brackets", uri.contains("<"));
        assertFalse("URI should not contain raw path separators", uri.contains("../"));
        String content = store.read(uri);
        assertEquals("safe", content);
    }
}
