package cn.lunalhx.ai.domain.tool.adapter.port;

import cn.lunalhx.ai.domain.tool.model.ToolCall;

import java.io.IOException;
import java.nio.file.Path;

public interface WorkspacePort {

    Path requireLocalRoot(ToolCall call) throws IOException;

}
