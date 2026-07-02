package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.tool.adapter.port.TaskLogReader;
import cn.lunalhx.ai.domain.tool.model.LogChunk;
import cn.lunalhx.ai.infrastructure.tool.SeekableTaskLogReader;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SeekableTaskLogReaderTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private TaskLogReader logReader;

    @Before
    public void setUp() {
        logReader = new SeekableTaskLogReader();
    }

    @Test
    public void asciiShouldReturnCorrectOffsets() throws Exception {
        String content = "hello world";
        Path file = temporaryFolder.newFile().toPath();
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));

        LogChunk chunk = logReader.readChunk(file, 0, 8192);
        assertEquals("hello world", chunk.getContent());
        assertEquals(11, chunk.getNextOffset());
        assertTrue(chunk.isEof());
        assertEquals(11, chunk.getTotalBytes());
    }

    @Test
    public void chineseShouldReturnOffsetInBytesNotChars() throws Exception {
        String content = "你好世界";
        byte[] utf8Bytes = content.getBytes(StandardCharsets.UTF_8);
        Path file = temporaryFolder.newFile().toPath();
        Files.write(file, utf8Bytes);

        LogChunk chunk = logReader.readChunk(file, 0, 8192);
        assertEquals("你好世界", chunk.getContent());
        assertEquals(utf8Bytes.length, chunk.getNextOffset());
        assertEquals(utf8Bytes.length, chunk.getTotalBytes());
    }

    @Test
    public void emojiShouldReturnCorrectBytes() throws Exception {
        String content = "hello \uD83D\uDE80 world";
        byte[] utf8Bytes = content.getBytes(StandardCharsets.UTF_8);
        Path file = temporaryFolder.newFile().toPath();
        Files.write(file, utf8Bytes);

        LogChunk chunk = logReader.readChunk(file, 0, 8192);
        assertEquals(content, chunk.getContent());
        assertEquals(utf8Bytes.length, chunk.getNextOffset());
        assertEquals(utf8Bytes.length, chunk.getTotalBytes());
    }

    @Test
    public void truncationAtMultiByteBoundaryShouldNotCorrupt() throws Exception {
        String content = "hello\u4E16\u754C"; // "hello世界"
        byte[] utf8Bytes = content.getBytes(StandardCharsets.UTF_8);
        Path file = temporaryFolder.newFile().toPath();
        Files.write(file, utf8Bytes);

        LogChunk chunk1 = logReader.readChunk(file, 0, 6);
        assertEquals("hello", chunk1.getContent());
        assertEquals(5, chunk1.getNextOffset());
        assertFalse(chunk1.isEof());

        LogChunk chunk2 = logReader.readChunk(file, chunk1.getNextOffset(), 8192);
        assertEquals("世界", chunk2.getContent());
        assertEquals(utf8Bytes.length, chunk2.getNextOffset());
        assertTrue(chunk2.isEof());
    }

    @Test
    public void multiPageContentShouldReassembleCorrectly() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("你好世界\uD83D\uDE80test");
        }
        String content = sb.toString();
        byte[] utf8Bytes = content.getBytes(StandardCharsets.UTF_8);
        Path file = temporaryFolder.newFile().toPath();
        Files.write(file, utf8Bytes);

        StringBuilder reassembled = new StringBuilder();
        long offset = 0;
        int pageSize = 50;
        while (true) {
            LogChunk chunk = logReader.readChunk(file, offset, pageSize);
            if (chunk.getContent() != null) {
                reassembled.append(chunk.getContent());
            }
            offset = chunk.getNextOffset();
            if (chunk.isEof()) {
                break;
            }
        }
        assertEquals(content, reassembled.toString());
    }

    @Test
    public void endOfFileShouldReturnEofTrue() throws Exception {
        Path file = temporaryFolder.newFile().toPath();
        Files.write(file, "abc".getBytes(StandardCharsets.UTF_8));

        LogChunk chunk = logReader.readChunk(file, 0, 8192);
        assertEquals("abc", chunk.getContent());
        assertEquals(3, chunk.getNextOffset());
        assertTrue(chunk.isEof());
    }

    @Test
    public void offsetPastEndShouldReturnEmpty() throws Exception {
        Path file = temporaryFolder.newFile().toPath();
        Files.write(file, "abc".getBytes(StandardCharsets.UTF_8));

        LogChunk chunk = logReader.readChunk(file, 10, 8192);
        assertEquals("", chunk.getContent());
        assertEquals(10, chunk.getNextOffset());
        assertTrue(chunk.isEof());
        assertEquals(3, chunk.getTotalBytes());
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeOffsetShouldThrow() throws Exception {
        Path file = temporaryFolder.newFile().toPath();
        Files.write(file, "abc".getBytes(StandardCharsets.UTF_8));
        logReader.readChunk(file, -1, 8192);
    }

    @Test(expected = IllegalArgumentException.class)
    public void limitBytesTooSmallShouldThrow() throws Exception {
        Path file = temporaryFolder.newFile().toPath();
        Files.write(file, "abc".getBytes(StandardCharsets.UTF_8));
        logReader.readChunk(file, 0, 3);
    }

    @Test(expected = IllegalArgumentException.class)
    public void limitBytesTooLargeShouldThrow() throws Exception {
        Path file = temporaryFolder.newFile().toPath();
        Files.write(file, "abc".getBytes(StandardCharsets.UTF_8));
        logReader.readChunk(file, 0, 2 * 1024 * 1024);
    }

    @Test
    public void nullFileShouldReturnEmpty() throws Exception {
        LogChunk chunk = logReader.readChunk(null, 0, 8192);
        assertNull(chunk.getContent());
        assertTrue(chunk.isEof());
    }

    @Test
    public void nonexistentFileShouldReturnEmpty() throws Exception {
        LogChunk chunk = logReader.readChunk(Path.of("/nonexistent/file.log"), 0, 8192);
        assertNull(chunk.getContent());
        assertTrue(chunk.isEof());
    }

    @Test
    public void emptyFileShouldReturnEof() throws Exception {
        Path file = temporaryFolder.newFile().toPath();
        Files.write(file, new byte[0]);

        LogChunk chunk = logReader.readChunk(file, 0, 8192);
        assertEquals("", chunk.getContent());
        assertEquals(0, chunk.getNextOffset());
        assertTrue(chunk.isEof());
    }
}
