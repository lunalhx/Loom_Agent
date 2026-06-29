package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.domain.tool.model.ToolOutputSanitization;
import org.apache.commons.lang3.StringUtils;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RegexToolOutputSanitizer implements ToolOutputSanitizer {

    private static final String UNTRUSTED_OPEN = "<untrusted_tool_output";
    private static final String UNTRUSTED_CLOSE = "</untrusted_tool_output>";
    private static final String UNTRUSTED_OPEN_ESCAPED = "&lt;untrusted_tool_output";
    private static final String UNTRUSTED_CLOSE_ESCAPED = "&lt;/untrusted_tool_output&gt;";

    private static final Pattern UNTRUSTED_OPEN_PATTERN =
            Pattern.compile("<untrusted_tool_output\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNTRUSTED_CLOSE_PATTERN =
            Pattern.compile("</untrusted_tool_output>", Pattern.CASE_INSENSITIVE);

    private static final String SYSTEM_ROLE_MARKER = "SYSTEM_ROLE_MARKER";
    private static final String IGNORE_PRIOR_INSTRUCTIONS_ZH = "IGNORE_PRIOR_INSTRUCTIONS_ZH";
    private static final String IGNORE_PRIOR_INSTRUCTIONS_EN = "IGNORE_PRIOR_INSTRUCTIONS_EN";
    private static final String ROLE_OVERRIDE = "ROLE_OVERRIDE";

    private static final Pattern SYSTEM_ROLE_PATTERN =
            Pattern.compile("\\[SYSTEM\\]", Pattern.CASE_INSENSITIVE);

    private static final Pattern IGNORE_ZH_PATTERN =
            Pattern.compile("(?:忽略|无视)\\s*(?:之前|以上).*?(?:指令|提示|规则)");

    private static final Pattern IGNORE_EN_PATTERN =
            Pattern.compile("(?:ignore|disregard)\\s+(?:previous|prior|above|all\\s+previous|all\\s+prior)\\s+(?:instructions?|prompts?|rules?|directives?)",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern ROLE_OVERRIDE_PATTERN =
            Pattern.compile("(?:你现在是|从现在起你是|现在你是|you\\s+are\\s+now|act\\s+as)",
                    Pattern.CASE_INSENSITIVE);

    @Override
    public ToolOutputSanitization sanitize(String toolName, String rawOutput) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return ToolOutputSanitization.clean(rawOutput == null ? "" : rawOutput);
        }
        String escaped = escapeUntrustedTags(rawOutput);
        String normalized = Normalizer.normalize(escaped, Normalizer.Form.NFKC).toLowerCase();

        Set<String> matchedRuleIds = new LinkedHashSet<>();
        if (SYSTEM_ROLE_PATTERN.matcher(normalized).find()) {
            matchedRuleIds.add(SYSTEM_ROLE_MARKER);
        }
        if (IGNORE_ZH_PATTERN.matcher(normalized).find()) {
            matchedRuleIds.add(IGNORE_PRIOR_INSTRUCTIONS_ZH);
        }
        if (IGNORE_EN_PATTERN.matcher(normalized).find()) {
            matchedRuleIds.add(IGNORE_PRIOR_INSTRUCTIONS_EN);
        }
        if (ROLE_OVERRIDE_PATTERN.matcher(normalized).find()) {
            matchedRuleIds.add(ROLE_OVERRIDE);
        }

        boolean detected = !matchedRuleIds.isEmpty();
        return ToolOutputSanitization.builder()
                .output(escaped)
                .injectionDetected(detected)
                .matchCount(matchedRuleIds.size())
                .matchedRuleIds(matchedRuleIds)
                .build();
    }

    private String escapeUntrustedTags(String raw) {
        String result = UNTRUSTED_OPEN_PATTERN.matcher(raw).replaceAll(UNTRUSTED_OPEN_ESCAPED);
        result = UNTRUSTED_CLOSE_PATTERN.matcher(result).replaceAll(UNTRUSTED_CLOSE_ESCAPED);
        return result;
    }

}
