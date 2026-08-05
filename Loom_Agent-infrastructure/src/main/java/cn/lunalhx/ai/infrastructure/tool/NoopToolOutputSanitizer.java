package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.domain.tool.model.ToolOutputSanitization;

/**
 * Pass-through tool output sanitizer. Per the loom-code port contract the
 * Java runtime keeps no extra prompt-injection scanning or output wrapping;
 * tool output is returned to the model verbatim (already clipped by the
 * executor).
 */
public class NoopToolOutputSanitizer implements ToolOutputSanitizer {

    @Override
    public ToolOutputSanitization sanitize(String toolName, String rawOutput) {
        return ToolOutputSanitization.clean(rawOutput);
    }
}
