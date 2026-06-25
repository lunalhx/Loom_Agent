package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.api.dto.AgentApprovalResponse;
import cn.lunalhx.ai.api.dto.AgentReplayEventDTO;
import cn.lunalhx.ai.api.dto.AgentReplayResponse;
import cn.lunalhx.ai.api.dto.AgentStreamEvent;
import cn.lunalhx.ai.api.dto.AgentTraceEventDTO;
import cn.lunalhx.ai.api.dto.AgentTraceTimelineResponse;
import cn.lunalhx.ai.api.dto.TokenUsageDTO;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentReplayTimeline;
import cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.TraceCost;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AgentResponseMapperTest {

    private AgentResponseMapper mapper;

    @Before
    public void setUp() {
        mapper = new AgentResponseMapper();
    }

    // ===== toStreamEvent =====

    @Test
    public void toStreamEventShouldMapAllFields() {
        AgentEvent event = AgentEvent.builder()
                .type(AgentEventType.ANSWER)
                .runId("r-1")
                .requestId("req-1")
                .conversationId("c-1")
                .workspace("/tmp")
                .parentRunId("p-1")
                .subAgentRunId("sub-1")
                .subAgentTaskId("task-1")
                .subAgentRole("role")
                .subAgentStatus("running")
                .elapsedMs(100L)
                .step(3)
                .node("model_call")
                .nodeInputs(List.of("in1"))
                .thought("thinking")
                .tool("write_file")
                .input(Map.of("k", "v"))
                .approvalId("ap-1")
                .permissionLevel("WRITE_CONFIRM")
                .riskReason("risk")
                .operationPreview("preview")
                .expiresAt(Instant.now())
                .observation("obs")
                .truncated(false)
                .answer("ans")
                .stopReason(AgentStopReason.FINAL_ANSWER)
                .stepCount(5)
                .code("0000")
                .message("ok")
                .plan(Map.of("plan", "p"))
                .checkpointVersion(1L)
                .metadata(Map.of("meta", "m"))
                .build();
        AgentStreamEvent dto = mapper.toStreamEvent(event);
        assertEquals("answer", dto.getType());
        assertEquals("r-1", dto.getRunId());
        assertEquals("req-1", dto.getRequestId());
        assertEquals("c-1", dto.getConversationId());
        assertEquals("/tmp", dto.getWorkspace());
        assertEquals("p-1", dto.getParentRunId());
        assertEquals("sub-1", dto.getSubAgentRunId());
        assertEquals("task-1", dto.getSubAgentTaskId());
        assertEquals("role", dto.getSubAgentRole());
        assertEquals("running", dto.getSubAgentStatus());
        assertEquals(Long.valueOf(100L), dto.getElapsedMs());
        assertEquals(Integer.valueOf(3), dto.getStep());
        assertEquals("model_call", dto.getNode());
        assertEquals(List.of("in1"), dto.getNodeInputs());
        assertEquals("thinking", dto.getThought());
        assertEquals("write_file", dto.getTool());
        assertEquals(Map.of("k", "v"), dto.getInput());
        assertEquals("ap-1", dto.getApprovalId());
        assertEquals("WRITE_CONFIRM", dto.getPermissionLevel());
        assertEquals("risk", dto.getRiskReason());
        assertEquals("preview", dto.getOperationPreview());
        assertNotNull(dto.getExpiresAt());
        assertEquals("obs", dto.getObservation());
        assertEquals(false, dto.getTruncated());
        assertEquals("ans", dto.getAnswer());
        assertEquals("FINAL_ANSWER", dto.getStopReason());
        assertEquals(Integer.valueOf(5), dto.getStepCount());
        assertEquals("0000", dto.getCode());
        assertEquals("ok", dto.getMessage());
        assertEquals(Map.of("plan", "p"), dto.getPlan());
        assertEquals(Long.valueOf(1L), dto.getCheckpointVersion());
        assertEquals(Map.of("meta", "m"), dto.getMetadata());
    }

    @Test
    public void toStreamEventNullStopReasonShouldMapToNull() {
        AgentEvent event = AgentEvent.builder().type(AgentEventType.DONE).build();
        AgentStreamEvent dto = mapper.toStreamEvent(event);
        assertNull(dto.getStopReason());
    }

    @Test
    public void toStreamEventNullExpiresAtShouldMapToNull() {
        AgentEvent event = AgentEvent.builder().type(AgentEventType.DONE).build();
        AgentStreamEvent dto = mapper.toStreamEvent(event);
        assertNull(dto.getExpiresAt());
    }

    // ===== toApprovalResponse =====

    @Test
    public void toApprovalResponseShouldMapAllFieldsAndStatusIsPending() {
        PendingApproval approval = PendingApproval.builder()
                .approvalId("ap-1").runId("r-1").requestId("req-1").conversationId("c-1")
                .workspaceDisplayName("ws").tool("write_file")
                .input(Map.of("path", "a.txt"))
                .permissionLevel(ToolPermissionLevel.WRITE_CONFIRM)
                .riskReason("risk").operationPreview("preview")
                .expiresAt(Instant.now())
                .build();
        AgentApprovalResponse dto = mapper.toApprovalResponse(approval);
        assertEquals("ap-1", dto.getApprovalId());
        assertEquals("r-1", dto.getRunId());
        assertEquals("PENDING", dto.getStatus());
        assertEquals("req-1", dto.getRequestId());
        assertEquals("c-1", dto.getConversationId());
        assertEquals("ws", dto.getWorkspace());
        assertEquals("write_file", dto.getTool());
        assertEquals(Map.of("path", "a.txt"), dto.getInput());
        assertEquals("WRITE_CONFIRM", dto.getPermissionLevel());
        assertEquals("risk", dto.getRiskReason());
        assertEquals("preview", dto.getOperationPreview());
        assertNotNull(dto.getExpiresAt());
    }

    @Test
    public void toApprovalResponseNullPermissionLevelShouldMapToNull() {
        PendingApproval approval = PendingApproval.builder().approvalId("ap-1").build();
        AgentApprovalResponse dto = mapper.toApprovalResponse(approval);
        assertNull(dto.getPermissionLevel());
    }

    // ===== toTraceTimeline =====

    @Test
    public void toTraceTimelineShouldMapRunIdTraceIdAndEvents() {
        AgentTraceEvent event = AgentTraceEvent.builder()
                .id(1L).traceId("t-1").rootRunId("r-1").runId("r-1")
                .sequenceNo(1L).eventType("node_start").node("model").build();
        AgentTraceTimelineResponse dto = mapper.toTraceTimeline("r-1", List.of(event));
        assertEquals("r-1", dto.getRunId());
        assertEquals("t-1", dto.getTraceId());
        assertEquals("r-1", dto.getRootRunId());
        assertEquals(1, dto.getEvents().size());
        assertEquals("node_start", dto.getEvents().get(0).getEventType());
    }

    // ===== toReplayResponse =====

    @Test
    public void toReplayResponseShouldMapAllFields() {
        AgentTraceEvent event = AgentTraceEvent.builder()
                .id(1L).sequenceNo(1L).eventType("node_start").runId("r-1").build();
        AgentReplayTimeline timeline = AgentReplayTimeline.builder()
                .mode("DRY_REPLAY").traceId("t-1").rootRunId("r-1").runId("r-1")
                .includeChildren(true).events(List.of(event)).costGenerated(false).build();
        AgentReplayResponse dto = mapper.toReplayResponse(timeline);
        assertEquals("DRY_REPLAY", dto.getMode());
        assertEquals("t-1", dto.getTraceId());
        assertEquals("r-1", dto.getRootRunId());
        assertEquals("r-1", dto.getRunId());
        assertTrue(dto.getIncludeChildren());
        assertEquals(1, dto.getEvents().size());
        assertEquals(false, dto.getCostGenerated());
    }

    // ===== Trace vs Replay DTO field differences =====

    @Test
    public void toTraceEventShouldHaveIdAndNodeFields() {
        AgentTraceEvent event = AgentTraceEvent.builder()
                .id(1L).traceId("t-1").rootRunId("r-1").runId("r-1")
                .sequenceNo(1L).eventType("node_start").node("model").build();
        AgentTraceEventDTO dto = mapper.toTraceEvent(event);
        assertEquals(Long.valueOf(1L), dto.getId());
        assertEquals("t-1", dto.getTraceId());
        assertEquals("r-1", dto.getRootRunId());
        assertEquals("model", dto.getNode());
    }

    @Test
    public void toReplayEventShouldHaveEventIdAndNodeNameFields() {
        AgentTraceEvent event = AgentTraceEvent.builder()
                .id(1L).sequenceNo(1L).eventType("node_start").runId("r-1")
                .node("model").build();
        AgentReplayEventDTO dto = mapper.toReplayEvent(event);
        assertEquals(Long.valueOf(1L), dto.getEventId());
        assertEquals("model", dto.getNodeName());
    }

    // ===== toTokenUsage =====

    @Test
    public void toTokenUsageShouldMapAllFields() {
        TokenUsage usage = TokenUsage.builder().promptTokens(100).completionTokens(50).totalTokens(150).build();
        TokenUsageDTO dto = mapper.toTokenUsage(usage);
        assertEquals(Integer.valueOf(100), dto.getPromptTokens());
        assertEquals(Integer.valueOf(50), dto.getCompletionTokens());
        assertEquals(Integer.valueOf(150), dto.getTotalTokens());
    }

    @Test
    public void toTokenUsageNullShouldReturnNull() {
        assertNull(mapper.toTokenUsage(null));
    }

    // ===== toCostMap =====

    @Test
    public void toCostMapShouldPreserveOrder() {
        TraceCost cost = TraceCost.builder()
                .inputCost(BigDecimal.valueOf(0.01))
                .outputCost(BigDecimal.valueOf(0.02))
                .totalCost(BigDecimal.valueOf(0.03))
                .build();
        Map<String, Object> result = mapper.toCostMap(cost);
        assertEquals(BigDecimal.valueOf(0.01), result.get("inputCost"));
        assertEquals(BigDecimal.valueOf(0.02), result.get("outputCost"));
        assertEquals(BigDecimal.valueOf(0.03), result.get("totalCost"));
        // LinkedHashMap preserves insertion order
        String[] keys = result.keySet().toArray(new String[0]);
        assertEquals("inputCost", keys[0]);
        assertEquals("outputCost", keys[1]);
        assertEquals("totalCost", keys[2]);
    }

    @Test
    public void toCostMapNullShouldReturnEmptyMap() {
        Map<String, Object> result = mapper.toCostMap(null);
        assertTrue(result.isEmpty());
    }

    // ===== Replay SSE payloads =====

    @Test
    public void replayStartedShouldHaveCorrectFields() {
        AgentReplayTimeline timeline = AgentReplayTimeline.builder()
                .traceId("t-1").rootRunId("r-1").build();
        Map<String, Object> result = mapper.replayStarted("r-1", true, timeline);
        assertEquals("replay_started", result.get("type"));
        assertEquals("DRY_REPLAY", result.get("mode"));
        assertEquals("r-1", result.get("runId"));
        assertEquals("t-1", result.get("traceId"));
        assertEquals("r-1", result.get("rootRunId"));
        assertEquals(true, result.get("includeChildren"));
        assertEquals(false, result.get("costGenerated"));
    }

    @Test
    public void replayStartedNullTraceIdRootRunIdShouldMapToEmptyString() {
        AgentReplayTimeline timeline = AgentReplayTimeline.builder().build();
        Map<String, Object> result = mapper.replayStarted("r-1", false, timeline);
        assertEquals("", result.get("traceId"));
        assertEquals("", result.get("rootRunId"));
    }

    @Test
    public void replayDoneShouldHaveCorrectFields() {
        Map<String, Object> result = mapper.replayDone("r-1", 5);
        assertEquals("replay_done", result.get("type"));
        assertEquals("DRY_REPLAY", result.get("mode"));
        assertEquals("r-1", result.get("runId"));
        assertEquals(5, result.get("eventCount"));
        assertEquals(false, result.get("costGenerated"));
    }

    @Test
    public void replayErrorShouldHaveCorrectFields() {
        Map<String, Object> result = mapper.replayError("replay_failed", "Replay 失败");
        assertEquals("error", result.get("type"));
        assertEquals("replay_failed", result.get("code"));
        assertEquals("Replay 失败", result.get("message"));
    }

    // ===== toTraceEvent with null fields =====

    @Test
    public void toTraceEventNullTokenUsageShouldMapToNull() {
        AgentTraceEvent event = AgentTraceEvent.builder().id(1L).sequenceNo(1L).eventType("node_start").build();
        AgentTraceEventDTO dto = mapper.toTraceEvent(event);
        assertNull(dto.getTokenUsage());
    }

    @Test
    public void toTraceEventNullCostShouldMapToEmptyMap() {
        AgentTraceEvent event = AgentTraceEvent.builder().id(1L).sequenceNo(1L).eventType("node_start").build();
        AgentTraceEventDTO dto = mapper.toTraceEvent(event);
        assertTrue(dto.getCost().isEmpty());
    }

    @Test
    public void toTraceEventNullCreatedAtShouldMapToNull() {
        AgentTraceEvent event = AgentTraceEvent.builder().id(1L).sequenceNo(1L).eventType("node_start").build();
        AgentTraceEventDTO dto = mapper.toTraceEvent(event);
        assertNull(dto.getCreatedAt());
    }
}
