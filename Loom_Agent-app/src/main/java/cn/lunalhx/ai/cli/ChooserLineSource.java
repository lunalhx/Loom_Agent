package cn.lunalhx.ai.cli;

/** Reads one line for an active-Run exit chooser. */
public interface ChooserLineSource {
    Read read(String prompt);

    sealed interface Read permits Line, Interrupt, Eof {}

    record Line(String value) implements Read {}

    record Interrupt() implements Read {}

    record Eof() implements Read {}
}
