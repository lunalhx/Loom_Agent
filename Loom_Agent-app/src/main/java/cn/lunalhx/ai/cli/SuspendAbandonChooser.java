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

    private final ChooserLineSource source;
    private final PrintStream output;

    public SuspendAbandonChooser(ChooserLineSource source, PrintStream output) {
        this.source = Objects.requireNonNull(source, "source");
        this.output = Objects.requireNonNull(output, "output");
    }

    public RunExitAction choose() {
        output.println(PROMPT);
        while (true) {
            ChooserLineSource.Read read = source.read("suspend/abandon> ");
            if (read instanceof ChooserLineSource.Interrupt || read instanceof ChooserLineSource.Eof) {
                output.println(PROMPT);
                continue;
            }
            if (read instanceof ChooserLineSource.Line line) {
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
}
