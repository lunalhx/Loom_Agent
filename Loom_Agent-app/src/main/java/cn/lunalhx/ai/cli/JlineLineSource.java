package cn.lunalhx.ai.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

import java.util.Objects;

/** Adapts a JLine {@link LineReader} into the suspend/abandon chooser. */
final class JlineLineSource implements SuspendAbandonChooser.LineSource {

    private final LineReader reader;

    JlineLineSource(LineReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    @Override
    public Read read(String prompt) {
        try {
            return new Line(reader.readLine(prompt));
        } catch (UserInterruptException e) {
            return new Interrupt();
        } catch (EndOfFileException e) {
            return new Eof();
        }
    }
}
