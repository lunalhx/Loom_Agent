package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.SubAgentControlMessage;
import cn.lunalhx.ai.domain.agent.model.valobj.MemoryStoreProperties;
import cn.lunalhx.ai.infrastructure.adapter.port.InMemorySubAgentControlInbox;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class InMemorySubAgentControlInboxTest {

    @Test
    public void pollReturnsOnlyNonExpiredMessages() {
        InMemorySubAgentControlInbox inbox = new InMemorySubAgentControlInbox();

        long now = System.currentTimeMillis();
        inbox.send("child-1", msg("child-1", now + 60000, "active"));
        inbox.send("child-1", msg("child-1", now - 1000, "expired"));

        List<SubAgentControlMessage> result = inbox.poll("child-1");
        assertEquals(1, result.size());
        assertEquals("active", result.get(0).getReason());
    }

    @Test
    public void pollRemovesExpiredMessagesFromStore() {
        MemoryStoreProperties props = new MemoryStoreProperties();
        props.setMaxSubAgentInboxes(10);
        props.setMaxSubAgentMessagesPerRun(10);
        InMemorySubAgentControlInbox inbox = new InMemorySubAgentControlInbox(props);

        long now = System.currentTimeMillis();
        inbox.send("child-1", msg("child-1", now - 1000, "expired"));
        inbox.send("child-1", msg("child-1", now + 60000, "active"));

        // First poll: should clean up expired, leave active
        List<SubAgentControlMessage> first = inbox.poll("child-1");
        assertEquals(1, first.size());
        assertEquals("active", first.get(0).getReason());

        // Second poll: only active should remain
        List<SubAgentControlMessage> second = inbox.poll("child-1");
        assertEquals(1, second.size());
    }

    @Test
    public void pollRemovesKeyWhenAllMessagesExpired() {
        MemoryStoreProperties props = new MemoryStoreProperties();
        props.setMaxSubAgentInboxes(10);
        props.setMaxSubAgentMessagesPerRun(10);
        InMemorySubAgentControlInbox inbox = new InMemorySubAgentControlInbox(props);

        long now = System.currentTimeMillis();
        inbox.send("child-1", msg("child-1", now - 2000, "expired-1"));
        inbox.send("child-1", msg("child-1", now - 1000, "expired-2"));

        List<SubAgentControlMessage> result = inbox.poll("child-1");
        assertTrue("all messages expired, should return empty", result.isEmpty());

        // Key should be removed; verify by checking poll returns empty (not null)
        List<SubAgentControlMessage> second = inbox.poll("child-1");
        assertTrue(second.isEmpty());
    }

    @Test
    public void clearRemovesAllMessagesForChildRun() {
        InMemorySubAgentControlInbox inbox = new InMemorySubAgentControlInbox();

        long deadline = System.currentTimeMillis() + 60000;
        inbox.send("child-1", msg("child-1", deadline, "m1"));
        inbox.send("child-1", msg("child-1", deadline, "m2"));

        inbox.clear("child-1");
        assertTrue(inbox.poll("child-1").isEmpty());
    }

    @Test
    public void sendRejectsNullArguments() {
        InMemorySubAgentControlInbox inbox = new InMemorySubAgentControlInbox();

        long deadline = System.currentTimeMillis() + 60000;
        inbox.send(null, msg("child-1", deadline, "m1"));
        inbox.send("child-1", null);
        inbox.send("  ", msg("child-1", deadline, "m1"));

        assertTrue(inbox.poll("child-1").isEmpty());
        assertTrue(inbox.poll(null).isEmpty());
    }

    // ---- helpers ----

    private static SubAgentControlMessage msg(String childRunId, long deadlineMs, String reason) {
        SubAgentControlMessage msg = new SubAgentControlMessage();
        msg.setChildRunId(childRunId);
        msg.setDeadlineMs(deadlineMs);
        msg.setReason(reason);
        return msg;
    }
}
