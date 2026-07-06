package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentPlan;
import cn.lunalhx.ai.domain.agent.model.entity.AgentPlanItem;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentPlanItemStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class AgentPlanTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void genericPlanShouldNotAssumeWorkspaceEdit() {
        AgentPlan plan = AgentPlan.forQuestion("解释这段代码");

        assertEquals(0, plan.incompleteEditItemCount());
    }

    @Test
    public void statusPatchShouldPreserveExistingOrder() throws Exception {
        AgentPlan plan = AgentPlan.forQuestion("普通任务");
        AgentPlanItem item = plan.getItems().get(1);
        Integer originalOrder = item.getOrder();

        plan.applyTodoWrite(objectMapper.readTree("""
                {"todos":[{"id":"task-2","status":"completed"}]}
                """));

        assertEquals(originalOrder, item.getOrder());
        assertEquals(AgentPlanItemStatus.COMPLETED, item.getStatus());
    }

    @Test
    public void editItemShouldRequireNormalizedRelativeTargets() throws Exception {
        AgentPlan plan = new AgentPlan();

        assertThrows(IllegalArgumentException.class, () -> plan.applyTodoWrite(
                objectMapper.readTree("""
                        {"todos":[{"content":"edit","status":"pending","kind":"edit"}]}
                        """)));

        plan.applyTodoWrite(objectMapper.readTree("""
                {"todos":[{"content":"edit","status":"pending","kind":"edit",
                  "targets":["./src/main/java/Demo.java"]}]}
                """));

        assertTrue(plan.hasDeclaredEditTarget("src/main/java/Demo.java"));
        assertFalse(plan.hasDeclaredEditTarget("src/main/java/OtherDemo.java"));
        assertEquals(List.of("src/main/java/Demo.java"),
                plan.getItems().get(0).getTargets());
    }
}
