package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.Application;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
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
            /mode plan|build  Show or change the collaboration mode.
            /sandbox workspace  Show the active sandbox selection.
            /sandbox full-access  Show the launch-scoped Full Access selection.
            /plan new  Start an independent Plan.
            /plan select <plan-id>  Select a Plan's latest revision.
            /plan handoff [plan-id]  Start a Build Run bound to a fresh Plan revision.
            /plan list  List persisted Plans and computed freshness.
            /plan show  Show the current Plan and computed freshness.
            /skills  List effective Skills, sources, and diagnostics.
            /new     Create a new independent session.
            /recover Continue the unfinished Run in Recovery Required.
            /recovery fact <text>  Add an external fact in Ambiguity Review.
            /recovery continue  Continue with Ambiguity from Ambiguity Review.
            /abandon Abandon the unfinished Run in Recovery Required.
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
                    if (handleControl(session, arguments.prompt.strip(), System.out)) {
                        return 0;
                    }
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
        System.setProperty("loom.ai.providers." + options.provider + ".top-p",
                String.valueOf(options.topP));
        System.setProperty("loom.ai.providers." + options.provider + ".timeout-seconds",
                String.valueOf(options.timeoutSeconds));
        System.setProperty("loom.ai.allowed-models", options.model);
        System.setProperty("loom.agent.workspace-root", options.workspaceRoot);
        System.setProperty("loom.agent.allowed-workspace-roots", options.workspaceRoot);
        System.setProperty("loom.agent.max-steps", String.valueOf(options.maxSteps));
        System.setProperty("loom.agent.approval-policy", options.approvalPolicy);
        if (!options.secretEnvNames.isEmpty()) {
            System.setProperty("loom.agent.secret-env-names",
                    String.join(",", options.secretEnvNames));
        }

        SpringApplication application = new SpringApplication(Application.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        application.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        return application.run(arguments == null ? new String[0] : new String[0]);
    }

    private static int repl(CliSessionService session) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("\nloom-code [" + session.collaborationMode().cliName() + "]> ");
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
            if (handleControl(session, input, System.out)) {
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
                case "/memory" -> {
                    System.out.println(session.memoryView());
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

    static boolean handleControl(CliSessionService session, String input, PrintStream output) {
        if (input.equals("/plan list")) {
            output.println(session.planListView());
            return true;
        }
        if (input.equals("/plan show")) {
            output.println(session.planShowView());
            return true;
        }
        if (input.equals("/skills")) {
            output.println(session.skillsCatalogView());
            return true;
        }
        if (input.equals("/plan new")) {
            try {
                session.newPlan();
                output.println("plan target: NEW");
            } catch (CliSessionService.OptionsException e) {
                output.println("error: " + e.getMessage());
            }
            return true;
        }
        if (input.startsWith("/plan select ")) {
            String planId = input.substring("/plan select ".length()).strip();
            try {
                session.selectPlan(planId);
                output.println("plan selected: " + planId);
            } catch (CliSessionService.OptionsException e) {
                output.println("error: " + e.getMessage());
            }
            return true;
        }
        if (input.equals("/plan handoff") || input.startsWith("/plan handoff ")) {
            String planId = input.equals("/plan handoff")
                    ? null : input.substring("/plan handoff ".length()).strip();
            try {
                output.println(session.handoffPlan(planId));
            } catch (CliSessionService.OptionsException e) {
                output.println("error: " + e.getMessage());
            }
            return true;
        }
        if (input.equals("/plan") || input.startsWith("/plan ")) {
            output.println("error: use /plan new, /plan select <plan-id>, /plan handoff [plan-id], /plan list, or /plan show");
            return true;
        }
        if (input.equals("/mode")) {
            output.println("mode: " + session.collaborationMode().cliName());
            return true;
        }
        if (input.equals("/sandbox workspace")) {
            output.println(session.fullAccessActive()
                    ? "sandbox: FULL ACCESS (workspace sandbox inactive)" : "sandbox: workspace");
            return true;
        }
        if (input.equals("/sandbox full-access")) {
            if (session.collaborationMode() == CollaborationMode.PLAN) {
                output.println("sandbox: FULL ACCESS inactive in Plan mode");
            } else if (session.fullAccessActive()) {
                output.println("sandbox: FULL ACCESS (launch-scoped)");
            } else {
                output.println("sandbox: FULL ACCESS not selected; restart with --full-access and confirm FULL ACCESS");
            }
            return true;
        }
        if (input.startsWith("/mode ")) {
            String value = input.substring("/mode ".length()).strip();
            try {
                CollaborationMode mode = CollaborationMode.parse(value);
                session.setCollaborationMode(mode);
                output.println("mode: " + mode.cliName());
            } catch (IllegalArgumentException | CliSessionService.OptionsException e) {
                output.println("error: mode must be build or plan");
            }
            return true;
        }
        if (input.equals("/recovery continue")) {
            try {
                output.println(session.continueWithAmbiguity());
            } catch (CliSessionService.OptionsException e) {
                output.println("error: " + e.getMessage());
            }
            return true;
        }
        if (input.startsWith("/recovery fact")) {
            String fact = input.equals("/recovery fact")
                    ? "" : input.substring("/recovery fact".length()).strip();
            try {
                output.println(session.addAmbiguityFact(fact));
            } catch (CliSessionService.OptionsException e) {
                output.println("error: " + e.getMessage());
            }
            return true;
        }
        if (input.equals("/recovery") || input.startsWith("/recovery ")) {
            output.println("error: use /recovery fact <text> or /recovery continue");
            return true;
        }
        return switch (input) {
            case "/new" -> {
                String previousId = session.sessionId();
                String newId = session.newSession();
                output.println("new session: " + newId + " (previous: " + previousId + ")");
                yield true;
            }
            case "/recover" -> {
                try {
                    output.println(session.recover());
                } catch (CliSessionService.OptionsException e) {
                    output.println("error: " + e.getMessage());
                }
                yield true;
            }
            case "/abandon" -> {
                try {
                    output.println(session.abandon());
                } catch (CliSessionService.OptionsException e) {
                    output.println("error: " + e.getMessage());
                }
                yield true;
            }
            case "/reset" -> {
                output.println("error: /reset is unavailable; use /new");
                yield true;
            }
            default -> false;
        };
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
        options.startupMode = arguments.mode;
        if (arguments.fullAccess) {
            if (arguments.mode == CollaborationMode.PLAN) {
                throw new CliSessionService.OptionsException("Full Access cannot be activated in Plan mode");
            }
            java.io.Console console = System.console();
            if (console == null) {
                throw new CliSessionService.OptionsException("--full-access requires an interactive terminal");
            }
            String confirmation = console.readLine("Type FULL ACCESS to confirm unrestricted host execution: ");
            if (!"FULL ACCESS".equals(confirmation)) {
                throw new CliSessionService.OptionsException("Full Access confirmation was not accepted");
            }
            options.fullAccess = true;
        }
        options.maxSteps = arguments.maxSteps;
        options.maxNewTokens = arguments.maxNewTokens;
        options.temperature = arguments.temperature;
        options.topP = arguments.topP;
        options.timeoutSeconds = arguments.timeoutSeconds;
        options.secretEnvNames.addAll(arguments.secretEnvNames);
        options.approvalPrompt = new CliApprovalPrompt(
                arguments.approval != null && "ask".equals(arguments.approval));
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
        if (options.fullAccess) System.out.println("sandbox: FULL ACCESS (launch-scoped)");
        System.out.println("mode: " + session.collaborationMode().cliName());
        session.recoveryRequiredRun().ifPresent(run -> {
            if (session.recoveryBlocked()) {
                System.out.println("Recovery Blocked: unfinished run " + run.getRunId());
                if (run.getQuestion() != null && !run.getQuestion().isBlank()) {
                    System.out.println("task: " + run.getQuestion());
                }
                System.out.println(session.recoveryBlockedReason());
                System.out.println("Use /abandon to discard the run.");
                return;
            }
            if (session.ambiguityReview()) {
                System.out.println(session.formatAmbiguityReview());
                return;
            }
            System.out.println("Recovery Required: unfinished run " + run.getRunId());
            if (run.getQuestion() != null && !run.getQuestion().isBlank()) {
                System.out.println("task: " + run.getQuestion());
            }
            System.out.println("Use /recover to continue or /abandon to discard the run.");
        });
    }
}
