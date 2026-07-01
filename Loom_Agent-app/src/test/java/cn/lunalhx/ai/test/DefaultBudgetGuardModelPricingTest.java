package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.BudgetCheckResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.TraceCost;
import cn.lunalhx.ai.domain.agent.service.budget.DefaultBudgetGuard;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;
import cn.lunalhx.ai.domain.model.valobj.ModelPricing;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import org.junit.Test;

import java.math.BigDecimal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DefaultBudgetGuardModelPricingTest {

    @Test
    public void shouldUseActualModelPricingForPreflightAndUsage() {
        AgentRuntimeProperties agentProperties = new AgentRuntimeProperties();
        agentProperties.getBudget().setEnabled(true);
        agentProperties.getBudget().setMaxTotalTokens(100000);
        agentProperties.getBudget().setMaxTotalCost(new BigDecimal("0.01"));
        ModelRuntimeProperties modelProperties = new ModelRuntimeProperties();
        modelProperties.getModelPricing().put("deepseek-v4-flash",
                new ModelPricing(BigDecimal.ZERO, new BigDecimal("0.001")));
        modelProperties.getModelPricing().put("deepseek-v4-pro",
                new ModelPricing(BigDecimal.ZERO, new BigDecimal("10")));
        DefaultBudgetGuard guard = new DefaultBudgetGuard(agentProperties, modelProperties);
        AgentContext context = new AgentContext();
        context.setRunId("budget-model");
        context.setRootRunId("budget-model");

        BudgetCheckResult flash = guard.checkBeforeModelCall(context, "model_call",
                "deepseek-v4-flash", ModelCallPurpose.CONTROL_JSON, "hello", 100);
        BudgetCheckResult pro = guard.checkBeforeModelCall(context, "model_call",
                "deepseek-v4-pro", ModelCallPurpose.CONTROL_JSON, "hello", 100);
        TraceCost cost = guard.recordModelUsage(context, "deepseek-v4-pro",
                TokenUsage.builder().promptTokens(0).completionTokens(1).totalTokens(1).build());

        assertTrue(flash.isAllowed());
        assertFalse(pro.isAllowed());
        assertEquals(new BigDecimal("0.01000000"), cost.getOutputCost());
    }

}
