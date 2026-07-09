package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.tool.model.ShellCommandAnalysis;
import cn.lunalhx.ai.domain.tool.model.ShellExecutionMode;
import cn.lunalhx.ai.domain.tool.model.ShellFeature;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class ShellCommandAnalyzer {

    private static final Set<String> PRIVILEGE_ESCALATION = Set.of(
            "sudo", "su", "shutdown", "reboot", "mkfs");

    private static final Pattern PIPE_TO_INTERPRETER = Pattern.compile(
            "(?<!\\|)\\|(?!\\|)[^|]*?\\b(sh|bash|zsh|dash|ksh|csh|fish|ash|python3?|perl|ruby|node)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern REMOTE_SCRIPT_EXEC = Pattern.compile(
            "(?:curl|wget)\\s[^|]*\\|\\s*(?:sh|bash|zsh|dash)\\b"
                    + "|(?:sh|bash|zsh|dash)\\s*<\\s*\\(\\s*(?:curl|wget)\\s"
                    + "|base64\\s[^|]*\\|\\s*(?:sh|bash|zsh|dash)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SENSITIVE_PATH_PATTERN = Pattern.compile(
            "(?:^|\\s)~/(?:\\.(?:ssh|aws|config|gnupg|kube|netrc)"
                    + "|etc|usr|bin|sbin|var|root|System|Library)"
                    + "|(?:^|\\s)/(?:etc|usr|bin|sbin|var|root|System|Library)/"
                    + "|(?:^|\\s)\\.(?:ssh|aws|config|gnupg|kube|netrc)(?:/|\\s|$)"
                    + "|(?:^|\\s)\\.git/config",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> SENSITIVE_FILE_PATTERNS = Set.of(
            ".env", ".pem", ".key", ".p12", "id_rsa", "id_ed25519");

    private static final Pattern ABSOLUTE_PATH_ARGS = Pattern.compile(
            "(?:^|\\s)/(?![/\\s=])");

    private static final Pattern DOTDOT_SEGMENT = Pattern.compile(
            "(?:^|[\\s/])\\.\\.(?:[\\s/]|$)");

    private ShellCommandAnalyzer() {
    }

    public static ShellCommandAnalysis analyze(String command) {
        if (command == null || command.isBlank()) {
            return ShellCommandAnalysis.builder()
                    .hardDenied(true)
                    .hardDenyReason("命令为空")
                    .riskTags(List.of("empty_command"))
                    .build();
        }

        // Step 1: Hard-deny scanner
        ShellCommandAnalysis hardDeny = runHardDenyScan(command);
        if (hardDeny != null) {
            return hardDeny;
        }

        // Step 2: Shell feature detection
        Set<ShellFeature> features = detectShellFeatures(command);

        // Step 3: Determine execution mode
        if (!features.isEmpty()) {
            return buildShellExecResult(command, features);
        }

        // Step 4: SIMPLE_EXEC - tokenize
        return buildSimpleExecResult(command);
    }

    // ------- Step 1: Hard-deny scanner -------

    private static ShellCommandAnalysis runHardDenyScan(String command) {
        ShellCommandAnalysis result;

        result = checkDestructiveRm(command);
        if (result != null) return result;

        result = checkPrivilegeEscalation(command);
        if (result != null) return result;

        result = checkRemoteScriptExecution(command);
        if (result != null) return result;

        result = checkDeviceWrite(command);
        if (result != null) return result;

        result = checkPipeToInterpreter(command);
        if (result != null) return result;

        result = checkSensitiveFiles(command);
        if (result != null) return result;

        result = checkSensitivePaths(command);
        if (result != null) return result;

        result = checkSystemWrite(command);
        if (result != null) return result;

        result = checkWorkspaceEscape(command);
        if (result != null) return result;

        return null;
    }

    private static ShellCommandAnalysis checkDestructiveRm(String command) {
        String lower = command.toLowerCase();
        if (!lower.matches(".*\\brm\\b.*")) {
            return null;
        }
        boolean hasRecursive = Pattern.compile("(?i)(?:^|\\s)-[a-zA-Z]*[rR]").matcher(command).find()
                || Pattern.compile("(?i)--recursive\\b").matcher(command).find();
        if (!hasRecursive) {
            return null;
        }
        boolean hasForce = Pattern.compile("(?i)(?:^|\\s)-[a-zA-Z]*f").matcher(command).find()
                || Pattern.compile("(?i)--force\\b").matcher(command).find();
        if (!hasForce) {
            return null;
        }
        if (!hasDangerousTarget(command)) {
            return null;
        }
        return ShellCommandAnalysis.builder()
                .hardDenied(true)
                .hardDenyReason("禁止破坏性删除操作")
                .riskTags(List.of("destructive_rm"))
                .build();
    }

    private static boolean hasDangerousTarget(String command) {
        if (Pattern.compile("(?:^|\\s)\\*").matcher(command).find()) return true;
        if (Pattern.compile("(?:^|\\s)/").matcher(command).find()) return true;
        if (Pattern.compile("(?:^|\\s)\\.\\.").matcher(command).find()) return true;
        if (Pattern.compile("(?:^|\\s)~").matcher(command).find()) return true;
        return Pattern.compile("(?:^|\\s)\\.(?:\\s|$)").matcher(command).find();
    }

    private static ShellCommandAnalysis checkPrivilegeEscalation(String command) {
        String lower = command.toLowerCase();
        for (String keyword : PRIVILEGE_ESCALATION) {
            if (Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b").matcher(lower).find()) {
                return ShellCommandAnalysis.builder()
                        .hardDenied(true)
                        .hardDenyReason("禁止特权操作")
                        .riskTags(List.of("sudo"))
                        .build();
            }
        }
        return null;
    }

    private static ShellCommandAnalysis checkRemoteScriptExecution(String command) {
        if (REMOTE_SCRIPT_EXEC.matcher(command).find()) {
            return ShellCommandAnalysis.builder()
                    .hardDenied(true)
                    .hardDenyReason("禁止远程脚本执行")
                    .riskTags(List.of("remote_script_execution"))
                    .build();
        }
        return null;
    }

    private static ShellCommandAnalysis checkDeviceWrite(String command) {
        if (Pattern.compile("dd\\s+.*of=/dev/", Pattern.CASE_INSENSITIVE).matcher(command).find()) {
            return ShellCommandAnalysis.builder()
                    .hardDenied(true)
                    .hardDenyReason("禁止直接写入设备")
                    .riskTags(List.of("device_write"))
                    .build();
        }
        return null;
    }

    private static ShellCommandAnalysis checkPipeToInterpreter(String command) {
        if (PIPE_TO_INTERPRETER.matcher(command).find()) {
            return ShellCommandAnalysis.builder()
                    .hardDenied(true)
                    .hardDenyReason("禁止管道传递到解释器执行")
                    .riskTags(List.of("pipe_to_shell"))
                    .build();
        }
        return null;
    }

    private static ShellCommandAnalysis checkSensitiveFiles(String command) {
        String lower = command.toLowerCase();
        for (String pattern : SENSITIVE_FILE_PATTERNS) {
            if (lower.contains(pattern)) {
                return ShellCommandAnalysis.builder()
                        .hardDenied(true)
                        .hardDenyReason("命令涉及敏感文件")
                        .riskTags(List.of("sensitive_file"))
                        .build();
            }
        }
        return null;
    }

    private static ShellCommandAnalysis checkSensitivePaths(String command) {
        if (SENSITIVE_PATH_PATTERN.matcher(command).find()) {
            return ShellCommandAnalysis.builder()
                    .hardDenied(true)
                    .hardDenyReason("命令涉及敏感路径")
                    .riskTags(List.of("sensitive_path"))
                    .build();
        }
        return null;
    }

    private static ShellCommandAnalysis checkSystemWrite(String command) {
        // Look for > or >> followed by a system path
        if (Pattern.compile("[>]{1,2}\\s*(/etc/|/usr/|/bin/|/sbin/)",
                Pattern.CASE_INSENSITIVE).matcher(command).find()) {
            return ShellCommandAnalysis.builder()
                    .hardDenied(true)
                    .hardDenyReason("禁止写入系统路径")
                    .riskTags(List.of("system_write"))
                    .build();
        }
        // Also check /dev/ for write redirection (dd already handled above, exclude /dev/null)
        if (Pattern.compile("[>]\\s*(?!/dev/null)/dev/", Pattern.CASE_INSENSITIVE).matcher(command).find()) {
            return ShellCommandAnalysis.builder()
                    .hardDenied(true)
                    .hardDenyReason("禁止写入系统路径")
                    .riskTags(List.of("system_write"))
                    .build();
        }
        return null;
    }

    private static ShellCommandAnalysis checkWorkspaceEscape(String command) {
        // Absolute paths as space-delimited tokens (not option values like --path=/foo)
        if (ABSOLUTE_PATH_ARGS.matcher(command).find()) {
            return ShellCommandAnalysis.builder()
                    .hardDenied(true)
                    .hardDenyReason("命令包含越权路径")
                    .riskTags(List.of("path_escape"))
                    .build();
        }
        // .. directory segments
        if (DOTDOT_SEGMENT.matcher(command).find()) {
            return ShellCommandAnalysis.builder()
                    .hardDenied(true)
                    .hardDenyReason("命令包含越权路径")
                    .riskTags(List.of("path_escape"))
                    .build();
        }
        // Sensitive directory names
        String lower = command.toLowerCase();
        if (lower.contains(".git/") || lower.matches(".*\\.git(?:\\s|$).*")
                || lower.contains(".idea/") || lower.matches(".*\\.idea(?:\\s|$).*")
                || lower.contains("node_modules/") || lower.matches(".*node_modules(?:\\s|$).*")) {
            return ShellCommandAnalysis.builder()
                    .hardDenied(true)
                    .hardDenyReason("命令包含越权路径")
                    .riskTags(List.of("path_escape"))
                    .build();
        }
        return null;
    }

    // ------- Step 2: Shell feature detection -------

    private static Set<ShellFeature> detectShellFeatures(String command) {
        Set<ShellFeature> features = EnumSet.noneOf(ShellFeature.class);
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);

            if (ch == '\n' || ch == '\r') {
                features.add(ShellFeature.MULTILINE);
                continue;
            }

            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }

            if (inSingleQuote) {
                continue;
            }

            if (inDoubleQuote) {
                // Inside double quotes: only check for variable expansion and command substitution
                if (ch == '$') {
                    if (i + 1 < command.length()) {
                        char next = command.charAt(i + 1);
                        if (next == '(') {
                            features.add(ShellFeature.COMMAND_SUBSTITUTION);
                            continue;
                        }
                        if (next == '{' || Character.isLetter(next) || next == '_') {
                            features.add(ShellFeature.VARIABLE_EXPANSION);
                            continue;
                        }
                    }
                }
                if (ch == '`') {
                    features.add(ShellFeature.COMMAND_SUBSTITUTION);
                }
                continue;
            }

            // Not in any quotes
            switch (ch) {
                case '|':
                    if (i + 1 < command.length() && command.charAt(i + 1) == '|') {
                        features.add(ShellFeature.LOGICAL_OP);
                        i++;
                    } else {
                        features.add(ShellFeature.PIPE);
                    }
                    break;

                case '&':
                    if (i + 1 < command.length() && command.charAt(i + 1) == '&') {
                        features.add(ShellFeature.LOGICAL_OP);
                        i++;
                    } else if (i > 0 && command.charAt(i - 1) == '>') {
                        // Part of >& redirect, REDIRECT was already added when '>' was seen
                        break;
                    } else if (i > 0 && Character.isDigit(command.charAt(i - 1))
                            && i + 1 < command.length() && command.charAt(i + 1) == '>') {
                        // Part of 2>&1 style, REDIRECT handled by > below
                        break;
                    } else if (i > 0 && Character.isWhitespace(command.charAt(i - 1))
                            && (i + 1 >= command.length() || Character.isWhitespace(command.charAt(i + 1)))) {
                        features.add(ShellFeature.BACKGROUND);
                    } else if (i == 0 && i + 1 < command.length()
                            && Character.isWhitespace(command.charAt(i + 1))) {
                        features.add(ShellFeature.BACKGROUND);
                    }
                    break;

                case '>':
                    features.add(ShellFeature.REDIRECT);
                    if (i + 1 < command.length() && command.charAt(i + 1) == '>') {
                        i++;
                    }
                    break;

                case '<':
                    features.add(ShellFeature.REDIRECT);
                    break;

                case '$':
                    if (i + 1 < command.length()) {
                        char next = command.charAt(i + 1);
                        if (next == '(') {
                            features.add(ShellFeature.COMMAND_SUBSTITUTION);
                            continue;
                        }
                        if (next == '{' || Character.isLetter(next) || next == '_') {
                            features.add(ShellFeature.VARIABLE_EXPANSION);
                            continue;
                        }
                    }
                    break;

                case '`':
                    features.add(ShellFeature.COMMAND_SUBSTITUTION);
                    break;

                case '*':
                case '?':
                    features.add(ShellFeature.WILDCARD);
                    break;

                case '[':
                    features.add(ShellFeature.WILDCARD);
                    break;

                case ';':
                    // Semicolon is a shell metacharacter; treat presence as requiring SHELL_EXEC
                    features.add(ShellFeature.LOGICAL_OP);
                    break;

                default:
                    break;
            }
        }

        return features;
    }

    // ------- Step 4: SIMPLE_EXEC tokenization -------

    private static ShellCommandAnalysis buildSimpleExecResult(String command) {
        List<String> tokens = tokenizeSimple(command);
        String primaryCommand = extractPrimaryFromTokens(tokens);

        return ShellCommandAnalysis.builder()
                .executionMode(ShellExecutionMode.SIMPLE_EXEC)
                .tokens(tokens)
                .features(EnumSet.noneOf(ShellFeature.class))
                .primaryCommand(primaryCommand)
                .hardDenied(false)
                .build();
    }

    private static List<String> tokenizeSimple(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;

        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);

            if (!singleQuoted && ch == '"') {
                doubleQuoted = !doubleQuoted;
                continue;
            }
            if (!doubleQuoted && ch == '\'') {
                singleQuoted = !singleQuoted;
                continue;
            }
            if (!singleQuoted && !doubleQuoted) {
                if (Character.isWhitespace(ch)) {
                    addToken(tokens, current);
                    continue;
                }
            }
            current.append(ch);
        }
        addToken(tokens, current);
        return tokens;
    }

    private static void addToken(List<String> tokens, StringBuilder current) {
        if (!current.isEmpty()) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    private static String extractPrimaryFromTokens(List<String> tokens) {
        if (tokens.isEmpty()) {
            return "";
        }
        String first = tokens.get(0);
        if (!"env".equals(first)) {
            return first;
        }
        // Skip env and VAR=value pairs
        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (!t.contains("=")) {
                return t;
            }
        }
        return "env";
    }

    // ------- Step 5: SHELL_EXEC primary command extraction -------

    private static ShellCommandAnalysis buildShellExecResult(String command, Set<ShellFeature> features) {
        String primaryCommand = extractPrimaryFromShellCommand(command);

        return ShellCommandAnalysis.builder()
                .executionMode(ShellExecutionMode.SHELL_EXEC)
                .rawCommand(command)
                .features(features)
                .primaryCommand(primaryCommand)
                .hardDenied(false)
                .build();
    }

    private static String extractPrimaryFromShellCommand(String command) {
        // Find the first unquoted separator and take everything before it
        int cutoff = findFirstUnquotedSeparator(command);
        String firstSegment = cutoff >= 0 ? command.substring(0, cutoff) : command;
        // Tokenize this segment to extract the primary command
        List<String> tokens = tokenizeSimple(firstSegment);
        return extractPrimaryFromTokens(tokens);
    }

    private static int findFirstUnquotedSeparator(String command) {
        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);

            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }
            if (ch == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }
            if (inSingle || inDouble) {
                continue;
            }

            // Check for separators
            if (ch == ';') {
                return i;
            }
            if (ch == '|') {
                return i;
            }
            if (ch == '&') {
                if (i + 1 < command.length() && command.charAt(i + 1) == '&') {
                    return i;
                }
            }
            if (ch == '<') {
                return i;
            }
            if (ch == '>') {
                return i;
            }
            if (ch == '2' && i + 1 < command.length() && command.charAt(i + 1) == '>') {
                return i;
            }
        }
        return -1;
    }
}
