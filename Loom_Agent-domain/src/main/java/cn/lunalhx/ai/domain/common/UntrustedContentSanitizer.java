package cn.lunalhx.ai.domain.common;

/**
 * Minimal XML escaping helpers for prompt rendering.
 */
public final class UntrustedContentSanitizer {

    private UntrustedContentSanitizer() {
    }

    public static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
