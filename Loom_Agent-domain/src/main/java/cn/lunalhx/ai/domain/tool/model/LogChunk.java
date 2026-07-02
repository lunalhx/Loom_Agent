package cn.lunalhx.ai.domain.tool.model;

public class LogChunk {

    private final String content;
    private final long nextOffset;
    private final boolean eof;
    private final long totalBytes;

    public LogChunk(String content, long nextOffset, boolean eof, long totalBytes) {
        this.content = content;
        this.nextOffset = nextOffset;
        this.eof = eof;
        this.totalBytes = totalBytes;
    }

    public static LogChunk empty(long offset, long totalBytes) {
        return new LogChunk(null, offset, true, totalBytes);
    }

    public String getContent() {
        return content;
    }

    public long getNextOffset() {
        return nextOffset;
    }

    public boolean isEof() {
        return eof;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

}
