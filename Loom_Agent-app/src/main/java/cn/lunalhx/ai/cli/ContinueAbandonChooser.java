package cn.lunalhx.ai.cli;

import java.io.PrintStream;
import java.util.Objects;

/**
 * Interactive continue vs abandon prompt for an active Full Access Run.
 * Suspend is not offered; Ctrl-C and EOF while choosing do not pick a default.
 */
public final class ContinueAbandonChooser {

    static final String PROMPT =
            "Active Full Access Run: type continue (return to the Run) or abandon (terminal). "
                    + "Suspend is not available. Ctrl-C/EOF does not choose.";

    public enum Choice {
        CONTINUE,
        ABANDON
    }

    private final ChooserLineSource source;
    private final PrintStream output;

    public ContinueAbandonChooser(ChooserLineSource source, PrintStream output) {
        this.source = Objects.requireNonNull(source, "source");
        this.output = Objects.requireNonNull(output, "output");
    }

    public Choice choose() {
        output.println(PROMPT);
        while (true) {
            ChooserLineSource.Read read = source.read("continue/abandon> ");
            if (read instanceof ChooserLineSource.Interrupt
                    || read instanceof ChooserLineSource.Eof) {
                output.println(PROMPT);
                continue;
            }
            if (read instanceof ChooserLineSource.Line line) {
                String choice = line.value() == null ? "" : line.value().strip().toLowerCase();
                if ("c".equals(choice) || "continue".equals(choice)) {
                    return Choice.CONTINUE;
                }
                if ("a".equals(choice) || "abandon".equals(choice)) {
                    return Choice.ABANDON;
                }
            }
        }
    }
}
