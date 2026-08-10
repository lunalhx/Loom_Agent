package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Small hand-rolled CLI argument parser for the loom-code Java runtime.
 *
 * <p>No external CLI framework: positional prompt, one-shot flags and REPL
 * defaults follow the Python loom-code {@code cli.py} contract.
 */
public final class CliArguments {

    public static final String DEFAULT_PROVIDER = "deepseek";
    public static final String DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-pro";
    public static final String DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com/anthropic";
    public static final String DEFAULT_OPENAI_MODEL = "gpt-5.4";
    public static final String DEFAULT_OPENAI_BASE_URL = "https://www.right.codes/codex/v1";
    public static final String DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-6";
    public static final String DEFAULT_ANTHROPIC_BASE_URL = "https://www.right.codes/claude/v1";
    public static final String DEFAULT_OLLAMA_MODEL = "qwen3.5:4b";
    public static final String DEFAULT_OLLAMA_HOST = "http://127.0.0.1:11434";
    public static final List<String> PROVIDER_CHOICES = List.of("ollama", "openai", "anthropic", "deepseek");

    public String prompt;
    public String cwd = ".";
    public String provider;
    public String model;
    public String baseUrl;
    public String host = DEFAULT_OLLAMA_HOST;
    public String resume;
    public String approval = "ask";
    /** Null means a resumed Session keeps its persisted mode. */
    public CollaborationMode mode;
    public final List<String> secretEnvNames = new ArrayList<>();
    public int maxSteps = 6;
    public int maxNewTokens = 512;
    public double temperature = 0.2;
    public double topP = 0.9;
    public long timeoutSeconds = 300;

    public static CliArguments parse(String[] args) {
        CliArguments result = new CliArguments();
        List<String> positional = new ArrayList<>();
        int i = 0;
        while (i < args.length) {
            String arg = args[i];
            switch (arg) {
                case "--cwd" -> result.cwd = requireValue(args, ++i, arg);
                case "--provider" -> result.provider = requireValue(args, ++i, arg);
                case "--model" -> result.model = requireValue(args, ++i, arg);
                case "--base-url" -> result.baseUrl = requireValue(args, ++i, arg);
                case "--host" -> result.host = requireValue(args, ++i, arg);
                case "--resume" -> result.resume = requireValue(args, ++i, arg);
                case "--approval" -> result.approval = requireValue(args, ++i, arg);
                case "--mode" -> result.mode = parseMode(requireValue(args, ++i, arg), arg);
                case "--secret-env-name" -> result.secretEnvNames.add(requireValue(args, ++i, arg));
                case "--max-steps" -> result.maxSteps = intValue(requireValue(args, ++i, arg), arg);
                case "--max-new-tokens" -> result.maxNewTokens = intValue(requireValue(args, ++i, arg), arg);
                case "--temperature" -> result.temperature = doubleValue(requireValue(args, ++i, arg), arg);
                case "--top-p" -> result.topP = doubleValue(requireValue(args, ++i, arg), arg);
                case "--timeout" -> result.timeoutSeconds = longValue(requireValue(args, ++i, arg), arg);
                default -> {
                    if (arg.startsWith("--")) {
                        throw new CliException("unknown option: " + arg);
                    }
                    positional.add(arg);
                }
            }
            i++;
        }
        result.prompt = positional.isEmpty() ? null : String.join(" ", positional).trim();
        result.validate();
        return result;
    }

    private void validate() {
        if (provider != null && !PROVIDER_CHOICES.contains(provider)) {
            throw new CliException("unknown provider: " + provider
                    + ". expected one of: " + String.join(", ", PROVIDER_CHOICES));
        }
        if (!List.of("ask", "auto", "never").contains(approval)) {
            throw new CliException("--approval must be ask, auto, or never");
        }
        if (maxSteps <= 0) {
            throw new CliException("--max-steps must be greater than zero");
        }
        if (maxNewTokens <= 0) {
            throw new CliException("--max-new-tokens must be greater than zero");
        }
    }

    private static CollaborationMode parseMode(String value, String option) {
        try {
            return CollaborationMode.parse(value);
        } catch (IllegalArgumentException e) {
            throw new CliException(option + " must be build or plan");
        }
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new CliException("missing value for " + option);
        }
        return args[index];
    }

    private static int intValue(String value, String option) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new CliException("invalid integer for " + option + ": " + value);
        }
    }

    private static long longValue(String value, String option) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new CliException("invalid number for " + option + ": " + value);
        }
    }

    private static double doubleValue(String value, String option) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new CliException("invalid number for " + option + ": " + value);
        }
    }

    public static class CliException extends RuntimeException {
        public CliException(String message) {
            super(message);
        }
    }
}
