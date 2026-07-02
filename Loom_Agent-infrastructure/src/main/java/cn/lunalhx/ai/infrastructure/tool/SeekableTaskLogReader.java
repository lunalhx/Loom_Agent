package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.tool.adapter.port.TaskLogReader;
import cn.lunalhx.ai.domain.tool.model.LogChunk;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class SeekableTaskLogReader implements TaskLogReader {

    @Override
    public LogChunk readChunk(Path file, long offset, int limitBytes) throws IOException {
        if (offset < 0 || limitBytes < MIN_LIMIT_BYTES || limitBytes > MAX_LIMIT_BYTES) {
            throw new IllegalArgumentException("offset must be >= 0, limitBytes must be [" + MIN_LIMIT_BYTES + ", " + MAX_LIMIT_BYTES + "]");
        }
        if (file == null || !Files.exists(file)) {
            return LogChunk.empty(0, 0);
        }

        long fileSize = Files.size(file);
        if (offset >= fileSize) {
            return new LogChunk("", offset, true, fileSize);
        }

        int readSize = (int) Math.min(limitBytes, fileSize - offset);

        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            channel.position(offset);
            ByteBuffer byteBuf = ByteBuffer.allocate(readSize);
            int bytesRead = channel.read(byteBuf);
            if (bytesRead <= 0) {
                return new LogChunk("", offset, true, fileSize);
            }
            byteBuf.flip();

            CharsetDecoder decoder = newUtf8Decoder();
            CharBuffer charBuf = CharBuffer.allocate(byteBuf.remaining() + 1);
            CoderResult result = decoder.decode(byteBuf, charBuf, true);
            decoder.flush(charBuf);

            String content;
            long consumedBytes;

            if (result.isError()) {
                int validEnd = findValidUtf8End(byteBuf, byteBuf.limit());
                decoder.reset();
                ByteBuffer validSlice = byteBuf.duplicate();
                validSlice.limit(validEnd);
                validSlice.position(0);
                charBuf.clear();
                decoder.decode(validSlice, charBuf, true);
                decoder.flush(charBuf);
                charBuf.flip();
                content = charBuf.toString();
                consumedBytes = validEnd;
            } else {
                charBuf.flip();
                content = charBuf.toString();
                consumedBytes = byteBuf.position();
            }

            long nextOffset = offset + consumedBytes;
            boolean eof = nextOffset >= fileSize;
            return new LogChunk(content, nextOffset, eof, fileSize);
        }
    }

    private static CharsetDecoder newUtf8Decoder() {
        CharsetDecoder d = StandardCharsets.UTF_8.newDecoder();
        d.onMalformedInput(CodingErrorAction.REPORT);
        d.onUnmappableCharacter(CodingErrorAction.REPORT);
        return d;
    }

    private int findValidUtf8End(ByteBuffer buffer, int totalBytes) {
        int limit = Math.min(totalBytes, buffer.limit());
        for (int i = limit; i > 0; ) {
            i--;
            byte b = buffer.get(i);
            if ((b & 0xC0) != 0x80) {
                int expected;
                if ((b & 0x80) == 0) {
                    expected = 1;
                } else if ((b & 0xE0) == 0xC0) {
                    expected = 2;
                } else if ((b & 0xF0) == 0xE0) {
                    expected = 3;
                } else if ((b & 0xF8) == 0xF0) {
                    expected = 4;
                } else {
                    expected = 1;
                }
                int actual = limit - i;
                if (actual >= expected) {
                    return limit;
                }
                return i;
            }
        }
        return 0;
    }

}
