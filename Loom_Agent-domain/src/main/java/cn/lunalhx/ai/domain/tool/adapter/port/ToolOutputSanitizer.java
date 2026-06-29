package cn.lunalhx.ai.domain.tool.adapter.port;

import cn.lunalhx.ai.domain.tool.model.ToolOutputSanitization;

public interface ToolOutputSanitizer {

    ToolOutputSanitization sanitize(String toolName, String rawOutput);

}
