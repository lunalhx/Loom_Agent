package cn.lunalhx.ai.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ticket 13 seam: Full Access active-Run exit offers continue or abandon.
 * Suspend is not a choice, and the first interrupt does not decide.
 */
public class ContinueAbandonChooserTest {

    @Test
    public void firstInterruptAndTypedSuspendDoNotSelectAnAction() {
        Queue<ChooserLineSource.Read> reads = new ArrayDeque<>(List.of(
                new ChooserLineSource.Interrupt(),
                new ChooserLineSource.Eof(),
                new ChooserLineSource.Line("suspend"),
                new ChooserLineSource.Line("continue")));
        AtomicInteger consumed = new AtomicInteger();
        ByteArrayOutputStream shown = new ByteArrayOutputStream();
        ContinueAbandonChooser chooser = new ContinueAbandonChooser(prompt -> {
            consumed.incrementAndGet();
            return reads.remove();
        }, new java.io.PrintStream(shown, true, StandardCharsets.UTF_8));

        assertEquals(ContinueAbandonChooser.Choice.CONTINUE, chooser.choose());
        assertEquals(4, consumed.get());
        String output = shown.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("continue"));
        assertTrue(output.contains("abandon"));
        assertTrue(output.contains("Suspend is not available"));
        assertTrue(output.contains("Ctrl-C/EOF does not choose"));
        assertFalse(output.contains("type suspend"));
    }

    @Test
    public void jlineReaderExecutesExplicitAbandonAfterInterrupts() {
        LineReader reader = mock(LineReader.class);
        when(reader.readLine(anyString()))
                .thenThrow(new UserInterruptException(""))
                .thenThrow(new EndOfFileException())
                .thenReturn("abandon");
        ByteArrayOutputStream shown = new ByteArrayOutputStream();
        ContinueAbandonChooser chooser = new ContinueAbandonChooser(
                new JlineLineSource(reader),
                new java.io.PrintStream(shown, true, StandardCharsets.UTF_8));
        assertEquals(ContinueAbandonChooser.Choice.ABANDON, chooser.choose());
        String output = shown.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("continue"));
        assertTrue(output.contains("abandon"));
        assertFalse(output.contains("type suspend"));
    }
}
