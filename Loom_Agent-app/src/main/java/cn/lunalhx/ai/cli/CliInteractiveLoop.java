package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.model.valobj.RunExitAction;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.PrintStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * JLine REPL. An active Run is left on a worker so Ctrl-C, EOF, and /exit can
 * present an explicit exit choice without implicitly deciding. Sandboxed Runs
 * choose Suspend vs Abandon; Full Access Runs choose continue vs Abandon.
 */
final class CliInteractiveLoop {

    private CliInteractiveLoop() {
    }

    static int run(CliSessionService session) {
        ExecutorService turns = Executors.newSingleThreadExecutor(thread -> {
            Thread worker = new Thread(thread, "cli-run-turn");
            worker.setDaemon(true);
            return worker;
        });
        try (Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .dumb(true)
                .jna(false)
                .jansi(false)
                .build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            PrintStream output = System.out;
            JlineLineSource lines = new JlineLineSource(reader);
            SuspendAbandonChooser recoverable = new SuspendAbandonChooser(lines, output);
            ContinueAbandonChooser fullAccess = new ContinueAbandonChooser(lines, output);
            while (true) {
                String line;
                try {
                    line = reader.readLine("\nloom-code [" + session.collaborationMode().cliName() + "]> ");
                } catch (UserInterruptException e) {
                    continue;
                } catch (EndOfFileException e) {
                    return 0;
                }
                if (line == null) {
                    return 0;
                }
                String input = line.strip();
                if (input.isEmpty()) {
                    continue;
                }
                if (CliMain.handleControl(session, input, output)) {
                    continue;
                }
                switch (input) {
                    case "/exit", "/quit" -> {
                        return 0;
                    }
                    case "/help" -> {
                        output.println(CliMain.helpText());
                        continue;
                    }
                    case "/session" -> {
                        output.println(session.sessionId());
                        continue;
                    }
                    case "/memory" -> {
                        output.println(session.memoryView());
                        continue;
                    }
                    default -> {
                    }
                }
                output.println();
                Future<String> turn = turns.submit(() -> runSafe(session, input));
                waitForTurn(session, reader, recoverable, fullAccess, turn);
                try {
                    output.println(turn.get(1, TimeUnit.MINUTES));
                } catch (Exception e) {
                    output.println("error: " + (e.getMessage() == null ? e : e.getMessage()));
                }
            }
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        } finally {
            turns.shutdownNow();
        }
    }

    private static void waitForTurn(CliSessionService session, LineReader reader,
                                    SuspendAbandonChooser recoverable,
                                    ContinueAbandonChooser fullAccess,
                                    Future<String> turn) {
        Thread waker = Thread.ofVirtual().name("cli-turn-waker").start(() -> {
            try {
                turn.get();
            } catch (Exception ignored) {
            }
            try {
                reader.getTerminal().raise(org.jline.terminal.Terminal.Signal.INT);
            } catch (Exception ignored) {
            }
        });
        try {
            while (!turn.isDone()) {
                try {
                    String line = reader.readLine(inProgressPrompt(session));
                    if (line != null) {
                        String stripped = line.strip();
                        if ("/exit".equals(stripped) || "/quit".equals(stripped)) {
                            applyExitChoice(session, recoverable, fullAccess);
                        }
                    }
                } catch (UserInterruptException | EndOfFileException e) {
                    if (turn.isDone()) {
                        return;
                    }
                    applyExitChoice(session, recoverable, fullAccess);
                }
            }
        } finally {
            waker.interrupt();
        }
    }

    private static String inProgressPrompt(CliSessionService session) {
        if (session.fullAccessActive()) {
            return "(run in progress; Ctrl-C, EOF, or /exit to choose continue/abandon)> ";
        }
        return "(run in progress; Ctrl-C, EOF, or /exit to choose suspend/abandon)> ";
    }

    private static void applyExitChoice(CliSessionService session,
                                        SuspendAbandonChooser recoverable,
                                        ContinueAbandonChooser fullAccess) {
        if (session.hasActiveRecoverableRun()) {
            RunExitAction action = recoverable.choose();
            if (action == RunExitAction.SUSPEND) {
                session.suspend();
            } else {
                session.abandon();
            }
            return;
        }
        if (session.hasActiveRun()) {
            if (fullAccess.choose() == ContinueAbandonChooser.Choice.ABANDON) {
                session.abandon();
            }
        }
    }

    private static String runSafe(CliSessionService session, String prompt) {
        try {
            return session.runTurn(prompt);
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }
}
