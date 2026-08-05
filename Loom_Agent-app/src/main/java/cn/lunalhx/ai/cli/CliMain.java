package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.Application;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * CLI entry point: one-shot mode when a prompt is given, otherwise a REPL.
 *
 * <p>Exit codes: 0 on success, non-zero on argument/provider/run errors.
 * Never listens on any port.
 */
public final class CliMain {

    private static final String HELP = """
            Commands:
            /help    Show this help message.
            /memory  Show the agent's distilled working memory.
            /session Show the session id.
            /reset   Clear the current session history and memory.
            /exit    Exit the agent.
            """;

    private CliMain() {
    }

    public static int run(String[] args) {
        CliArguments arguments;
        try {
            arguments = CliArguments.parse(args);
        } catch (CliArguments.CliException e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        }

        Path cwd = Path.of(arguments.cwd).toAbsolutePath().normalize();
        ProjectEnvironment env;
        try {
            env = ProjectEnvironment.load(cwd);
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        }
        CliSessionService.CliOptions options;
        try {
            options = resolveOptions(arguments, env);
        } catch (CliSessionService.OptionsException e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        }

        try (ConfigurableApplicationContext spring = startSpring(arguments, options)) {
            try (CliSessionService session = new CliSessionService(spring, options)) {
                printWelcome(session, options);
                if (arguments.prompt != null && !arguments.prompt.isBlank()) {
                    System.out.println();
                    System.out.println(runSafe(session, arguments.prompt));
                    return 0;
                }
                return repl(session);
            }
        } catch (CliSessionService.OptionsException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }

    private static ConfigurableApplicationContext startSpring(CliArguments arguments,
                                                              CliSessionService.CliOptions options) {
        System.setProperty("loom.ai.provider", options.provider);
        System.setProperty("loom.ai.providers." + options.provider + ".base-url", options.baseUrl);
        System.setProperty("loom.ai.providers." + options.provider + ".api-key", options.apiKey);
        System.setProperty("loom.ai.providers." + options.provider + ".default-model", options.model);
        System.setProperty("loom.ai.providers." + options.provider + ".temperature",
                String.valueOf(options.temperature));
        System.setProperty("loom.ai.providers." + options.provider + ".max-tokens",
                String.valueOf(options.maxNewTokens));
        System.setProperty("loom.ai.allowed-models", options.model);
        System.setProperty("loom.agent.workspace-root", options.workspaceRoot);
        System.setProperty("loom.agent.allowed-workspace-roots", options.workspaceRoot);
        System.setProperty("loom.agent.max-steps", String.valueOf(options.maxSteps));
        System.setProperty("loom.agent.approval-policy", options.approvalPolicy);

        SpringApplication application = new SpringApplication(Application.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        application.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        return application.run(arguments == null ? new String[0] : new String[0]);
    }

    private static int repl(CliSessionService session) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("\nloom-code> ");
            System.out.flush();
            String line;
            try {
                line = reader.readLine();
            } catch (Exception e) {
                System.out.println();
                return 0;
            }
            if (line == null) {
                System.out.println();
                return 0;
            }
            String input = line.strip();
            if (input.isEmpty()) {
                continue;
            }
            switch (input) {
                case "/exit", "/quit" -> {
                    return 0;
                }
                case "/help" -> {
                    System.out.println(HELP);
                    continue;
                }
                case "/session" -> {
                    System.out.println(session.sessionId());
                    continue;
                }
                case "/reset" -> {
                    session.reset();
                    System.out.println("session reset");
                    continue;
                }
                case "/memory" -> {
                    System.out.println("(working memory shown in prompt)");
                    continue;
                }
                default -> {
                    // fall through to ask
                }
            }
            System.out.println();
            System.out.println(runSafe(session, input));
        }
    }

    private static String runSafe(CliSessionService session, String prompt) {
        try {
            return session.runTurn(prompt);
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private static CliSessionService.CliOptions resolveOptions(CliArguments arguments, ProjectEnvironment env) {
        CliSessionService.CliOptions options = new CliSessionService.CliOptions();
        String provider = arguments.provider != null
                ? arguments.provider
                : env.getOr("LOOM_CODE_PROVIDER", CliArguments.DEFAULT_PROVIDER);
        if (!CliArguments.PROVIDER_CHOICES.contains(provider)) {
            throw new CliSessionService.OptionsException("unknown provider: " + provider
                    + ". expected one of: " + String.join(", ", CliArguments.PROVIDER_CHOICES));
        }
        options.provider = provider;

        switch (provider) {
            case "openai" -> {
                options.model = arguments.model != null ? arguments.model
                        : env.getOr("LOOM_CODE_OPENAI_MODEL", CliArguments.DEFAULT_OPENAI_MODEL);
                options.baseUrl = arguments.baseUrl != null ? arguments.baseUrl
                        : env.getOr("LOOM_CODE_OPENAI_API_BASE", CliArguments.DEFAULT_OPENAI_BASE_URL);
                options.apiKey = env.getOr("LOOM_CODE_OPENAI_API_KEY", env.getOr("LOOM_CODE_RIGHT_CODES_API_KEY", ""));
            }
            case "anthropic" -> {
                options.model = arguments.model != null ? arguments.model
                        : env.getOr("LOOM_CODE_ANTHROPIC_MODEL", CliArguments.DEFAULT_ANTHROPIC_MODEL);
                options.baseUrl = arguments.baseUrl != null ? arguments.baseUrl
                        : env.getOr("LOOM_CODE_ANTHROPIC_API_BASE", CliArguments.DEFAULT_ANTHROPIC_BASE_URL);
                options.apiKey = env.getOr("LOOM_CODE_ANTHROPIC_API_KEY", env.getOr("LOOM_CODE_RIGHT_CODES_API_KEY", ""));
            }
            case "deepseek" -> {
                options.model = arguments.model != null ? arguments.model
                        : env.getOr("LOOM_CODE_DEEPSEEK_MODEL", CliArguments.DEFAULT_DEEPSEEK_MODEL);
                options.baseUrl = arguments.baseUrl != null ? arguments.baseUrl
                        : env.getOr("LOOM_CODE_DEEPSEEK_API_BASE", CliArguments.DEFAULT_DEEPSEEK_BASE_URL);
                options.apiKey = env.getOr("LOOM_CODE_DEEPSEEK_API_KEY", "");
            }
            default -> {
                options.model = arguments.model != null ? arguments.model
                        : env.getOr("LOOM_CODE_OLLAMA_MODEL", CliArguments.DEFAULT_OLLAMA_MODEL);
                options.baseUrl = arguments.baseUrl != null ? arguments.baseUrl
                        : arguments.host != null ? arguments.host : CliArguments.DEFAULT_OLLAMA_HOST;
                options.apiKey = "";
            }
        }
        options.workspaceRoot = Path.of(arguments.cwd).toAbsolutePath().normalize().toString();
        options.approvalPolicy = arguments.approval;
        options.maxSteps = arguments.maxSteps;
        options.maxNewTokens = arguments.maxNewTokens;
        options.temperature = arguments.temperature;
        options.topP = arguments.topP;
        options.timeoutSeconds = arguments.timeoutSeconds;
        if (arguments.resume != null && !arguments.resume.isBlank()) {
            options.resumeSessionId = "latest".equals(arguments.resume)
                    ? sessionStoreLatest(options.workspaceRoot)
                    : arguments.resume;
            if (options.resumeSessionId == null) {
                throw new CliSessionService.OptionsException("no session found to resume");
            }
        }
        return options;
    }

    private static String sessionStoreLatest(String workspaceRoot) {
        try {
            java.nio.file.Path root = java.nio.file.Path.of(workspaceRoot, ".loom-code", "sessions");
            if (!java.nio.file.Files.isDirectory(root)) {
                return null;
            }
            java.nio.file.Path best = null;
            long bestMtime = -1;
            try (var stream = java.nio.file.Files.list(root)) {
                for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) stream
                        .filter(p -> p.toString().endsWith(".json"))::iterator) {
                    long mtime = java.nio.file.Files.getLastModifiedTime(file).toMillis();
                    if (mtime > bestMtime) {
                        bestMtime = mtime;
                        best = file;
                    }
                }
            }
            if (best == null) {
                return null;
            }
            String name = best.getFileName().toString();
            return name.substring(0, name.length() - ".json".length());
        } catch (Exception e) {
            return null;
        }
    }

    private static void printWelcome(CliSessionService session, CliSessionService.CliOptions options) {
        System.out.println("loom-code (java) - local coding agent");
        System.out.println("provider: " + options.provider + "  model: " + options.model);
        System.out.println("workspace: " + options.workspaceRoot);
        System.out.println("approval: " + options.approvalPolicy + "  session: " + session.sessionId());
    }
}
