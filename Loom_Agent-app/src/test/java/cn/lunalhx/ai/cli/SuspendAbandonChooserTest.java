package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.model.valobj.RunExitAction;
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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ticket 11 seam: JLine (and the chooser it drives) shows suspend/abandon
 * and does not treat the first interrupt as a decision.
 */
public class SuspendAbandonChooserTest {

    @Test
    public void firstInterruptAndEofDoNotSelectAnAction() {
        Queue<SuspendAbandonChooser.LineSource.Read> reads = new ArrayDeque<>(List.of(
                new SuspendAbandonChooser.LineSource.Interrupt(),
                new SuspendAbandonChooser.LineSource.Eof(),
                new SuspendAbandonChooser.LineSource.Interrupt(),
                new SuspendAbandonChooser.LineSource.Line("suspend")));
        AtomicInteger consumed = new AtomicInteger();
        ByteArrayOutputStream shown = new ByteArrayOutputStream();
        SuspendAbandonChooser chooser = new SuspendAbandonChooser(prompt -> {
            consumed.incrementAndGet();
            return reads.remove();
        }, new java.io.PrintStream(shown, true, StandardCharsets.UTF_8));

        assertEquals(RunExitAction.SUSPEND, chooser.choose());
        assertEquals(4, consumed.get());
        String output = shown.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("suspend"));
        assertTrue(output.contains("abandon"));
        assertTrue(output.contains("Ctrl-C/EOF does not choose"));
    }

    @Test
    public void jlineReaderExecutesExplicitAbandonAfterInterrupts() {
        LineReader reader = mock(LineReader.class);
        when(reader.readLine(anyString()))
                .thenThrow(new UserInterruptException(""))
                .thenThrow(new EndOfFileException())
                .thenReturn("abandon");
        ByteArrayOutputStream shown = new ByteArrayOutputStream();
        SuspendAbandonChooser chooser = new SuspendAbandonChooser(
                new JlineLineSource(reader),
                new java.io.PrintStream(shown, true, StandardCharsets.UTF_8));
        assertEquals(RunExitAction.ABANDON, chooser.choose());
        assertTrue(shown.toString(StandardCharsets.UTF_8).contains("suspend"));
    }
}
