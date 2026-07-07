package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentPlan;
import cn.lunalhx.ai.domain.agent.model.entity.AgentPlanEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentPlanItem;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentPlanItemStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

    @Test
    public void idBasedUpdateShouldNotMatchByContent() throws Exception {
        AgentPlan plan = new AgentPlan();
        plan.applyTodoWrite(objectMapper.readTree("""
                {"todos":[{"content":"task A","status":"pending","kind":"inspect"}]}
                """));
        // Creating with same content should fail
        assertThrows(IllegalArgumentException.class, () -> plan.applyTodoWrite(
                objectMapper.readTree("""
                        {"todos":[{"content":"task A","status":"pending","kind":"inspect"}]}
                        """)));
        // But updating by id should work
        String id = plan.getItems().get(0).getId();
        plan.applyTodoWrite(objectMapper.readTree("""
                {"todos":[{"id":"%s","status":"completed","evidence":"done"}]}
                """.formatted(id)));
        assertEquals(AgentPlanItemStatus.COMPLETED, plan.getItems().get(0).getStatus());
        assertEquals("done", plan.getItems().get(0).getEvidence());
    }

    @Test
    public void contentOnlyShouldNotUpdateExistingItem() throws Exception {
        AgentPlan plan = new AgentPlan();
        plan.applyTodoWrite(objectMapper.readTree("""
                {"todos":[{"content":"original","status":"pending","kind":"inspect"}]}
                """));
        // content-only without id should fail due to duplicate check
        assertThrows(IllegalArgumentException.class, () -> plan.applyTodoWrite(
                objectMapper.readTree("""
                        {"todos":[{"content":"original","status":"completed"}]}
                        """)));
    }

    @Test
    public void eachApplyShouldAppendEventLog() throws Exception {
        AgentPlan plan = new AgentPlan();
        plan.applyTodoWrite(objectMapper.readTree("""
                {"todos":[{"content":"item","status":"pending","kind":"edit",
                  "targets":["src/Foo.java"]}]}
                """));
        assertFalse(plan.getEvents().isEmpty());
        AgentPlanEvent first = plan.getEvents().get(0);
        assertEquals("CREATE", first.getType());
        assertEquals(1, first.getSequence());

        String id = plan.getItems().get(0).getId();
        plan.applyTodoWrite(objectMapper.readTree("""
                {"todos":[{"id":"%s","status":"completed"}]}
                """.formatted(id)));
        assertEquals(2, plan.getEvents().size());
        AgentPlanEvent second = plan.getEvents().get(1);
        assertEquals("UPDATE", second.getType());
        assertEquals(2, second.getSequence());
        assertEquals(id, second.getItemId());
    }

    @Test
    public void eventSequenceShouldBeMonotonic() throws Exception {
        AgentPlan plan = new AgentPlan();
        plan.addReplanItem("replan task", "test_reason");
        plan.addReplanItem("another replan task", "another_reason");
        plan.addReplanItem("replan task", "dup_reason"); // dedup

        assertFalse(plan.getEvents().isEmpty());
        int lastSeq = 0;
        for (AgentPlanEvent e : plan.getEvents()) {
            assertTrue(e.getSequence() > lastSeq);
            lastSeq = e.getSequence();
        }
    }

    @Test
    public void checkpointDeserializationShouldHaveEmptyEvents() throws Exception {
        // Old plan JSON without events field should deserialize with empty events
        String oldPlanJson = "{\"planId\":\"abc\",\"version\":1,\"currentItemId\":null," +
                "\"roundsSinceUpdate\":0,\"items\":[]}";
        AgentPlan plan = objectMapper.readValue(oldPlanJson, AgentPlan.class);
        assertNotNull(plan.getItems());
        assertNotNull(plan.getEvents());
        assertTrue(plan.getEvents().isEmpty());
    }

    @Test
    public void activeEditItemShouldReturnInProgressItem() throws Exception {
        AgentPlan plan = new AgentPlan();
        plan.applyTodoWrite(objectMapper.readTree("""
                {"todos":[
                  {"content":"edit A","status":"pending","kind":"edit","targets":["a.txt"]},
                  {"content":"edit B","status":"in_progress","kind":"edit","targets":["b.txt"]}
                ]}"""));
        AgentPlanItem active = plan.activeEditItem();
        assertNotNull(active);
        assertEquals("edit B", active.getContent());
        assertTrue(plan.hasActiveEditTarget("b.txt"));
        assertFalse(plan.hasActiveEditTarget("a.txt"));
    }

    @Test
    public void activeEditItemShouldReturnUniqueIncompleteWhenNoneInProgress() throws Exception {
        AgentPlan plan = new AgentPlan();
        plan.applyTodoWrite(objectMapper.readTree("""
                {"todos":[
                  {"content":"edit A","status":"completed","kind":"edit","targets":["a.txt"]},
                  {"content":"edit B","status":"pending","kind":"edit","targets":["b.txt"]}
                ]}"""));
        AgentPlanItem active = plan.activeEditItem();
        assertNotNull(active);
        assertEquals("edit B", active.getContent());
    }

    @Test
    public void activeEditItemShouldReturnNullWhenMultipleIncompleteCandidates() throws Exception {
        AgentPlan plan = new AgentPlan();
        plan.applyTodoWrite(objectMapper.readTree("""
                {"todos":[
                  {"content":"edit A","status":"pending","kind":"edit","targets":["a.txt"]},
                  {"content":"edit B","status":"pending","kind":"edit","targets":["b.txt"]}
                ]}"""));
        assertNull(plan.activeEditItem());
    }

    @Test
    public void currentEditableTargetsShouldReturnActiveTargets() throws Exception {
        AgentPlan plan = new AgentPlan();
        plan.applyTodoWrite(objectMapper.readTree("""
                {"todos":[
                  {"content":"edit active","status":"in_progress","kind":"edit","targets":["active.txt"]},
                  {"content":"edit other","status":"pending","kind":"edit","targets":["other.txt"]}
                ]}"""));
        List<String> targets = plan.currentEditableTargets();
        assertEquals(List.of("active.txt"), targets);
    }

    @Test
    public void blockItemShouldSetBlockedStatus() throws Exception {
        AgentPlan plan = new AgentPlan();
        plan.applyTodoWrite(objectMapper.readTree("""
                {"todos":[{"content":"task","status":"in_progress","kind":"edit",
                  "targets":["x.txt"]}]}"""));
        String id = plan.getItems().get(0).getId();
        plan.blockItem(id, "too many failures");
        assertEquals(AgentPlanItemStatus.BLOCKED, plan.getItems().get(0).getStatus());
        assertEquals("too many failures", plan.getItems().get(0).getBlocker());
    }

    @Test
    public void toViewShouldIncludeRecentEvents() throws Exception {
        AgentPlan plan = new AgentPlan();
        plan.applyTodoWrite(objectMapper.readTree("""
                {"todos":[{"content":"task","status":"pending","kind":"inspect"}]}
                """));
        Map<String, Object> view = plan.toView();
        assertTrue(view.containsKey("recentEvents"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) view.get("recentEvents");
        assertFalse(events.isEmpty());
    }

    @Test
    public void addReplanItemShouldEmitDedupEvent() throws Exception {
        AgentPlan plan = new AgentPlan();
        plan.addReplanItem("my task", "test");
        // Adding same content again should not duplicate but emit dedup event
        plan.addReplanItem("my task", "test again");
        // Should have 1 item and 2 events (REPLAN_APPEND + REPLAN_DEDUPED)
        long appendCount = plan.getEvents().stream()
                .filter(e -> "REPLAN_APPEND".equals(e.getType())).count();
        long dedupCount = plan.getEvents().stream()
                .filter(e -> "REPLAN_DEDUPED".equals(e.getType())).count();
        assertEquals(1, appendCount);
        assertEquals(1, dedupCount);
        assertEquals(1, plan.getItems().size());
    }

    @Test
    public void sameEditTargetsWithDifferentWordingShouldBeRejected() throws Exception {
        AgentPlan plan = new AgentPlan();
        plan.applyTodoWrite(objectMapper.readTree("""
                {"todos":[{"content":"创建 index.html","status":"pending","kind":"edit","targets":["index.html"]}]}
                """));
        assertThrows(IllegalArgumentException.class, () -> plan.applyTodoWrite(
                objectMapper.readTree("""
                        {"todos":[{"content":"创建 index.html 文件","status":"pending","kind":"edit","targets":["index.html"]}]}
                        """)));
        assertTrue(plan.getEvents().stream().anyMatch(e -> "DUPLICATE_CREATE_REJECTED".equals(e.getType())));
    }

    @Test
    public void differentTargetsWithSimilarContentShouldBeAllowed() throws Exception {
        AgentPlan plan = new AgentPlan();
        plan.applyTodoWrite(objectMapper.readTree("""
                {"todos":[{"content":"create file","status":"pending","kind":"edit","targets":["index.html"]}]}
                """));
        plan.applyTodoWrite(objectMapper.readTree("""
                {"todos":[{"content":"create file","status":"pending","kind":"edit","targets":["styles.css"]}]}
                """));
        assertEquals(2, plan.getItems().size());
    }

    @Test
    public void terminalTaskDuplicateShouldAllowCreationWithLog() throws Exception {
        AgentPlan plan = new AgentPlan();
        plan.applyTodoWrite(objectMapper.readTree("""
                {"todos":[{"content":"initial task","status":"completed","kind":"edit","targets":["index.html"]}]}
                """));
        // Different content, same targets — original is terminal (completed), so creation is allowed
        plan.applyTodoWrite(objectMapper.readTree("""
                {"todos":[{"content":"updated task","status":"pending","kind":"edit","targets":["index.html"]}]}
                """));
        assertEquals(2, plan.getItems().size());
        assertTrue(plan.getEvents().stream().anyMatch(e -> "CREATE_AFTER_TERMINAL_DUPLICATE".equals(e.getType())));
    }

    @Test
    public void oldJsonWithoutDedupeKeyShouldDeserialize() throws Exception {
        String json = """
                {"planId":"abc","version":1,"currentItemId":null,"roundsSinceUpdate":0,"items":[{"id":"task-1","order":1,"content":"test","status":"PENDING","kind":"inspect","targets":["a.txt"]}]}
                """;
        AgentPlan plan = objectMapper.readValue(json, AgentPlan.class);
        assertNotNull(plan.getItems());
        assertEquals(1, plan.getItems().size());
        assertNull(plan.getItems().get(0).getDedupeKey());
    }

    @Test
    public void dedupeKeyPresentAfterCreateAndEventsMonotonic() throws Exception {
        AgentPlan plan = new AgentPlan();
        plan.applyTodoWrite(objectMapper.readTree("""
                {"todos":[{"content":"edit Foo","status":"pending","kind":"edit","targets":["src/Foo.java"]}]}
                """));
        assertNotNull(plan.getItems().get(0).getDedupeKey());

        plan.applyTodoWrite(objectMapper.readTree("""
                {"todos":[{"content":"verify test","status":"pending","kind":"verify","verification":{"command":"mvn test"}}]}
                """));
        assertEquals("verify:mvn test", plan.getItems().get(1).getDedupeKey());

        // Event sequence should be monotonic
        int lastSeq = 0;
        for (AgentPlanEvent e : plan.getEvents()) {
            assertTrue(e.getSequence() > lastSeq);
            lastSeq = e.getSequence();
        }
    }

    @Test
    public void verifyTaskWithSameCommandShouldBeDuplicate() throws Exception {
        AgentPlan plan = new AgentPlan();
        plan.applyTodoWrite(objectMapper.readTree("""
                {"todos":[{"content":"run tests","status":"pending","kind":"verify","verification":{"command":"mvn test -pl mymodule"}}]}
                """));
        assertThrows(IllegalArgumentException.class, () -> plan.applyTodoWrite(
                objectMapper.readTree("""
                        {"todos":[{"content":"execute maven test","status":"pending","kind":"verify","verification":{"command":"mvn test -pl mymodule"}}]}
                        """)));
        assertTrue(plan.getEvents().stream().anyMatch(e -> "DUPLICATE_CREATE_REJECTED".equals(e.getType())));
    }
}
