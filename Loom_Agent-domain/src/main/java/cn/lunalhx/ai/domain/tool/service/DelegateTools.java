package cn.lunalhx.ai.domain.tool.service;

/** The bounded investigation tool whose parent-visible result is a Delegate Result. */
public final class DelegateTools {

    public static final String NAME = "delegate";

    private DelegateTools() {
    }

    public static boolean isDelegate(String toolName) {
        return NAME.equals(toolName);
    }
}
