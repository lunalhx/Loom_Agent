package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.WorkspaceRef;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

@Component
public class LocalWorkspacePort implements WorkspacePort {

    @Override
    public Path requireLocalRoot(ToolCall call) throws IOException {
        WorkspaceRef workspace = call == null ? null : call.workspaceRef();
        if (workspace == null) {
            throw new IOException("workspace 未解析");
        }
        if (!workspace.isLocal()) {
            throw new IOException("当前部署仅支持 local workspace provider：" + workspace.getProvider());
        }
        try {
            return workspace.requireLocalPath().toRealPath();
        } catch (Exception e) {
            throw new IOException("workspace 本地路径不可用：" + workspace.getLocation(), e);
        }
    }

}
