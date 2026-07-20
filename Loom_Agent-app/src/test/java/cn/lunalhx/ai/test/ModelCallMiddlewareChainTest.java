package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.flow.middleware.*;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ModelCallMiddlewareChainTest {

    // Test 1: Empty chain delegates to terminal
    @Test
    public void emptyChainShouldInvokeTerminal() {
        ModelCallNext terminal = ctx -> ModelCallOutcome.success();
        ModelCallMiddlewareChain chain = new ModelCallMiddlewareChain(List.of(), terminal);
        ModelCallContext ctx = ModelCallContext.of(new AgentContext(), "deepseek-v4-flash", 2048, null);
        ModelCallOutcome outcome = chain.execute(ctx);
        assertEquals(ModelCallOutcome.Type.SUCCESS, outcome.type());
    }

    // Test 2: Onion ordering — middleware wraps terminal
    @Test
    public void onionOrderingShouldWrapTerminal() {
        AtomicInteger order = new AtomicInteger(0);
        List<Integer> trace = new ArrayList<>();

        ModelCallMiddleware mw1 = (ctx, next) -> {
            trace.add(order.getAndIncrement());
            return next.invoke(ctx);
        };
        ModelCallMiddleware mw2 = (ctx, next) -> {
            trace.add(order.getAndIncrement());
            return next.invoke(ctx);
        };

        ModelCallNext terminal = ctx -> {
            trace.add(order.getAndIncrement());
            return ModelCallOutcome.success();
        };

        ModelCallMiddlewareChain chain = new ModelCallMiddlewareChain(List.of(mw1, mw2), terminal);
        chain.execute(ModelCallContext.of(new AgentContext(), "deepseek-v4-flash", 2048, null));

        assertEquals(List.of(0, 1, 2), trace);
    }

    // Test 3: Short-circuit — middleware returns without calling next
    @Test
    public void shortCircuitShouldSkipRemainingAndTerminal() {
        AtomicInteger invoked = new AtomicInteger(0);

        ModelCallMiddleware blocking = (ctx, next) -> {
            invoked.incrementAndGet();
            return ModelCallOutcome.budgetBlocked();
        };
        ModelCallMiddleware shouldNotRun = (ctx, next) -> {
            invoked.incrementAndGet();
            return next.invoke(ctx);
        };
        ModelCallNext terminal = ctx -> {
            invoked.incrementAndGet();
            return ModelCallOutcome.success();
        };

        ModelCallMiddlewareChain chain = new ModelCallMiddlewareChain(List.of(blocking, shouldNotRun), terminal);
        ModelCallOutcome outcome = chain.execute(ModelCallContext.of(new AgentContext(), "deepseek-v4-flash", 2048, null));

        assertEquals(1, invoked.get());
        assertEquals(ModelCallOutcome.Type.BUDGET_BLOCKED, outcome.type());
    }

    // Test 4: Context field modification visible to downstream
    @Test
    public void contextModificationShouldBeVisibleDownstream() {
        ModelCallMiddleware modifier = (ctx, next) -> {
            ctx.setMaxTokens(4096);
            return next.invoke(ctx);
        };

        ModelCallNext terminal = ctx -> {
            if (ctx.getMaxTokens() == 4096) {
                return ModelCallOutcome.success();
            }
            return ModelCallOutcome.error("maxTokens not modified");
        };

        ModelCallMiddlewareChain chain = new ModelCallMiddlewareChain(List.of(modifier), terminal);
        ModelCallOutcome outcome = chain.execute(ModelCallContext.of(new AgentContext(), "deepseek-v4-flash", 2048, null));

        assertEquals(ModelCallOutcome.Type.SUCCESS, outcome.type());
    }

    // Test 5: Exception propagates through chain
    @Test(expected = RuntimeException.class)
    public void exceptionShouldPropagateThroughChain() {
        ModelCallMiddleware throwing = (ctx, next) -> {
            throw new RuntimeException("middleware failure");
        };

        ModelCallNext terminal = ctx -> ModelCallOutcome.success();
        ModelCallMiddlewareChain chain = new ModelCallMiddlewareChain(List.of(throwing), terminal);
        chain.execute(ModelCallContext.of(new AgentContext(), "deepseek-v4-flash", 2048, null));
    }

    // Test 6: Each invocation uses independent context — no shared mutable state
    @Test
    public void eachInvocationShouldUseIndependentContext() {
        ModelCallNext terminal = ctx -> {
            ctx.setMaxTokens(999);
            return ModelCallOutcome.success();
        };

        ModelCallMiddlewareChain chain = new ModelCallMiddlewareChain(List.of(), terminal);

        ModelCallContext ctx1 = ModelCallContext.of(new AgentContext(), "deepseek-v4-flash", 2048, null);
        ModelCallContext ctx2 = ModelCallContext.of(new AgentContext(), "deepseek-v4-flash", 4096, null);

        chain.execute(ctx1);
        chain.execute(ctx2);

        assertEquals(Integer.valueOf(999), ctx1.getMaxTokens());
        assertEquals(Integer.valueOf(999), ctx2.getMaxTokens());
        // Each context is a separate object, so mutations on one don't affect the other
        assertNotSame(ctx1, ctx2);
    }

    // Test 7: ModelCallOutcome static factories produce correct NodeResult routes
    @Test
    public void outcomeFactoriesShouldProduceCorrectRoutes() {
        // SUCCESS
        ModelCallOutcome success = ModelCallOutcome.success();
        assertEquals(ModelCallOutcome.Type.SUCCESS, success.type());
        assertNull(success.route());
        assertNull(success.errorMessage());

        // BUDGET_BLOCKED -> FAIL (terminal)
        ModelCallOutcome budgetBlocked = ModelCallOutcome.budgetBlocked();
        assertEquals(ModelCallOutcome.Type.BUDGET_BLOCKED, budgetBlocked.type());
        assertNotNull(budgetBlocked.route());
        assertTrue(budgetBlocked.route().isTerminal());
        assertEquals(AgentNodeNames.FAIL, budgetBlocked.route().getNextNode());

        // TRUNCATION_EXHAUSTED -> USER_INPUT_GATE (not terminal)
        ModelCallOutcome truncationExhausted = ModelCallOutcome.truncationExhausted();
        assertEquals(ModelCallOutcome.Type.TRUNCATION_EXHAUSTED, truncationExhausted.type());
        assertNotNull(truncationExhausted.route());
        assertFalse(truncationExhausted.route().isTerminal());
        assertEquals(AgentNodeNames.USER_INPUT_GATE, truncationExhausted.route().getNextNode());

        // ERROR -> FAIL (terminal)
        ModelCallOutcome error = ModelCallOutcome.error("something went wrong");
        assertEquals(ModelCallOutcome.Type.ERROR, error.type());
        assertNotNull(error.route());
        assertTrue(error.route().isTerminal());
        assertEquals(AgentNodeNames.FAIL, error.route().getNextNode());
        assertEquals("something went wrong", error.errorMessage());

        // ROUTED
        NodeResult customRoute = NodeResult.next("custom_node", List.of());
        ModelCallOutcome routed = ModelCallOutcome.routed(customRoute);
        assertEquals(ModelCallOutcome.Type.ROUTED, routed.type());
        assertSame(customRoute, routed.route());
        assertNull(routed.errorMessage());
    }
}
