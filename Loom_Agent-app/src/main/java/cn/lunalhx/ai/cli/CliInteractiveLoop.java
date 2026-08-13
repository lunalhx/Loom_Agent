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
 * JLine REPL. An active sandboxed Run is left on a worker so Ctrl-C, EOF, and
 * /exit can present Suspend vs Abandon without implicitly deciding.
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
            SuspendAbandonChooser chooser = new SuspendAbandonChooser(new JlineLineSource(reader), output);
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
                waitForTurn(session, reader, chooser, turn);
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
                                    SuspendAbandonChooser chooser, Future<String> turn) {
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
                    String line = reader.readLine(
                            "(run in progress; Ctrl-C, EOF, or /exit to choose suspend/abandon)> ");
                    if (line != null) {
                        String stripped = line.strip();
                        if ("/exit".equals(stripped) || "/quit".equals(stripped)) {
                            applyExitChoice(session, chooser);
                        }
                    }
                } catch (UserInterruptException | EndOfFileException e) {
                    if (turn.isDone()) {
                        return;
                    }
                    applyExitChoice(session, chooser);
                }
            }
        } finally {
            waker.interrupt();
        }
    }

    private static void applyExitChoice(CliSessionService session, SuspendAbandonChooser chooser) {
        if (!session.hasActiveRecoverableRun()) {
            return;
        }
        RunExitAction action = chooser.choose();
        if (action == RunExitAction.SUSPEND) {
            session.suspend();
        } else {
            session.abandon();
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
