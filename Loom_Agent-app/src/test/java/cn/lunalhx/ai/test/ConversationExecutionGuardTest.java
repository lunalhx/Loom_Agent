package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.service.conversation.ConversationExecutionGuard;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertEquals;

public class ConversationExecutionGuardTest {

    private final ConversationExecutionGuard guard = new ConversationExecutionGuard();

    @Test
    public void sameKeyShouldAllowOnlyOneAcquire() {
        String token1 = guard.tryAcquire("conv-1");
        String token2 = guard.tryAcquire("conv-1");

        assertNotNull(token1);
        assertNull(token2);
    }

    @Test
    public void releaseShouldAllowReacquire() {
        String token = guard.tryAcquire("conv-2");
        assertNotNull(token);

        guard.release("conv-2", token);

        String token2 = guard.tryAcquire("conv-2");
        assertNotNull(token2);
    }

    @Test
    public void wrongTokenShouldNotRelease() {
        String token = guard.tryAcquire("conv-3");
        assertNotNull(token);

        guard.release("conv-3", "wrong-token");

        String token2 = guard.tryAcquire("conv-3");
        assertNull(token2);
    }

    @Test
    public void differentKeysShouldAllowConcurrentAcquire() {
        String token1 = guard.tryAcquire("conv-a");
        String token2 = guard.tryAcquire("conv-b");

        assertNotNull(token1);
        assertNotNull(token2);
        assertNotEquals(token1, token2);
    }

    @Test
    public void effectiveLockKeyShouldPreferConversationId() {
        assertEquals("conv-x", ConversationExecutionGuard.effectiveLockKey("conv-x", "run-x"));
    }

    @Test
    public void effectiveLockKeyShouldFallbackToRunId() {
        assertEquals("run-x", ConversationExecutionGuard.effectiveLockKey(null, "run-x"));
        assertEquals("run-x", ConversationExecutionGuard.effectiveLockKey("", "run-x"));
    }

    @Test
    public void effectiveLockKeyShouldReturnNullWhenBothBlank() {
        assertNull(ConversationExecutionGuard.effectiveLockKey(null, null));
        assertNull(ConversationExecutionGuard.effectiveLockKey("", ""));
        assertNull(ConversationExecutionGuard.effectiveLockKey("", null));
    }

    @Test
    public void blankKeyShouldReturnNullFromTryAcquire() {
        assertNull(guard.tryAcquire(null));
        assertNull(guard.tryAcquire(""));
        assertNull(guard.tryAcquire("   "));
    }
}
