package cn.lunalhx.ai.domain.tool.adapter.port;

import cn.lunalhx.ai.domain.tool.model.LogChunk;

import java.io.IOException;
import java.nio.file.Path;

public interface TaskLogReader {

    int MIN_LIMIT_BYTES = 4;
    int MAX_LIMIT_BYTES = 1024 * 1024;

    LogChunk readChunk(Path file, long offset, int limitBytes) throws IOException;

}
