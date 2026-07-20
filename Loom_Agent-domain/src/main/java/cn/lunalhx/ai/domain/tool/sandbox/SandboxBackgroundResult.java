package cn.lunalhx.ai.domain.tool.sandbox;

import cn.lunalhx.ai.domain.tool.model.BackgroundShellTask;

public record SandboxBackgroundResult(boolean started,
                                      String errorCode,
                                      String message,
                                      BackgroundShellTask task) {
}
