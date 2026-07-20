package cn.lunalhx.ai.domain.common;

import java.util.regex.Pattern;

public final class UntrustedContentSanitizer {

    private static final Pattern UNESCAPED_AMPERSAND = Pattern.compile(
            "&(?!(?:amp|lt|gt|quot|#39);)");

    private UntrustedContentSanitizer() {
    }

    public static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return UNESCAPED_AMPERSAND.matcher(value).replaceAll("&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
