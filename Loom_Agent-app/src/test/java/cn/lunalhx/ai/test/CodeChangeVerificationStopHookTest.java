package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookContext;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookEvent;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.runtime.hook.CodeChangeVerificationStopHook;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CodeChangeVerificationStopHookTest {

    private AgentRuntimeProperties properties;
    private CodeChangeVerificationStopHook hook;

    @Before
    public void setUp() {
        properties = AgentRuntimeTestFixture.standardProperties();
        properties.getExecutionGuards().setVerificationAfterWrite(true);
        properties.getExecutionGuards().setMaxVerificationContinuations(1);
        hook = new CodeChangeVerificationStopHook(properties);
    }

    @Test
    public void writeWithoutTestShouldContinueAtReplan() {
        AgentContext context = context();
        context.setLastWriteStep(3);

        AgentHookResult result = stop(context);

        assertTrue(result.isContinue());
        assertEquals(AgentNodeNames.REPLAN, result.getAction().getNextNode());
        assertEquals(1, context.getVerificationContinuationCount());
    }

    @Test
    public void failedTestAtRetryLimitShouldFailVerification() {
        AgentContext context = context();
        context.setLastWriteStep(3);
        context.setLastTestStep(4);
        context.setLastTestPassed(false);
        context.setVerificationContinuationCount(1);

        AgentHookResult result = stop(context);

        assertTrue(result.isContinue());
        assertEquals(AgentNodeNames.FAIL, result.getAction().getNextNode());
        assertEquals("verification_incomplete", context.getErrorCode());
    }

    @Test
    public void passingTestAfterLastWriteShouldAllowDone() {
        AgentContext context = context();
        context.setLastWriteStep(3);
        context.setLastTestStep(5);
        context.setLastTestPassed(true);
        context.setChangedSincePassingTest(false);

        AgentHookResult result = stop(context);

        assertFalse(result.isContinue());
    }

    @Test
    public void writeAfterPassingTestShouldRequireVerificationAgain() {
        AgentContext context = context();
        context.setLastWriteStep(6);
        context.setLastTestStep(5);
        context.setLastTestPassed(true);
        context.setChangedSincePassingTest(true);

        AgentHookResult result = stop(context);

        assertTrue(result.isContinue());
        assertEquals(AgentNodeNames.REPLAN, result.getAction().getNextNode());
    }

    private AgentHookResult stop(AgentContext context) {
        return hook.onEvent(
                AgentHookEvent.STOP,
                AgentHookContext.builder()
                        .agentContext(context)
                        .node(AgentNodeNames.FINAL_ANSWER)
                        .build());
    }

    private AgentContext context() {
        AgentContext context = new AgentContext();
        context.setRunId("verification-run");
        context.setRequestId("verification-request");
        context.setConversationId("verification-conversation");
        return context;
    }
}
