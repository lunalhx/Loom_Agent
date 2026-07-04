package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.SubAgentControlMessage;
import cn.lunalhx.ai.infrastructure.adapter.port.InMemorySubAgentControlInbox;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
    public void clearRemovesAllMessagesForChildRun() {
        InMemorySubAgentControlInbox inbox = new InMemorySubAgentControlInbox();

        long deadline = System.currentTimeMillis() + 60000;
        inbox.send("child-1", msg("child-1", deadline, "m1"));
        inbox.send("child-1", msg("child-1", deadline, "m2"));

        inbox.clear("child-1");
        assertTrue(inbox.poll("child-1").isEmpty());
    }

    private static SubAgentControlMessage msg(String childRunId, long deadlineMs, String reason) {
        SubAgentControlMessage msg = new SubAgentControlMessage();
        msg.setChildRunId(childRunId);
        msg.setDeadlineMs(deadlineMs);
        msg.setReason(reason);
        return msg;
    }
}
