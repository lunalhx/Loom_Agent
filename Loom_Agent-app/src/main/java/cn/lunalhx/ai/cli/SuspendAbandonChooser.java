package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.model.valobj.RunExitAction;

import java.io.PrintStream;
import java.util.Objects;

/**
 * Interactive Suspend vs Abandon prompt. Ctrl-C and EOF while choosing do
 * not pick a default; the user must type an explicit action.
 */
public final class SuspendAbandonChooser {

    static final String PROMPT =
            "Active Run: type suspend (recoverable) or abandon (terminal). "
                    + "Ctrl-C/EOF does not choose.";

    private final LineSource source;
    private final PrintStream output;

    public SuspendAbandonChooser(LineSource source, PrintStream output) {
        this.source = Objects.requireNonNull(source, "source");
        this.output = Objects.requireNonNull(output, "output");
    }

    public RunExitAction choose() {
        output.println(PROMPT);
        while (true) {
            LineSource.Read read = source.read("suspend/abandon> ");
            if (read instanceof LineSource.Interrupt || read instanceof LineSource.Eof) {
                output.println(PROMPT);
                continue;
            }
            if (read instanceof LineSource.Line line) {
                String choice = line.value() == null ? "" : line.value().strip().toLowerCase();
                if ("s".equals(choice) || "suspend".equals(choice)) {
                    return RunExitAction.SUSPEND;
                }
                if ("a".equals(choice) || "abandon".equals(choice)) {
                    return RunExitAction.ABANDON;
                }
            }
        }
    }

    public interface LineSource {
        Read read(String prompt);

        sealed interface Read permits Line, Interrupt, Eof {}

        record Line(String value) implements Read {}

        record Interrupt() implements Read {}

        record Eof() implements Read {}
    }
}
