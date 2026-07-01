package cn.lunalhx.ai.test;

import cn.lunalhx.ai.api.dto.AgentApprovalDecisionRequest;
import cn.lunalhx.ai.api.dto.AgentApprovalResponse;
import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import cn.lunalhx.ai.trigger.http.AgentApprovalController;
import cn.lunalhx.ai.trigger.http.agent.AgentApprovalHttpQueryService;
import cn.lunalhx.ai.trigger.http.agent.AgentRequestMapper;
import cn.lunalhx.ai.trigger.http.agent.AgentResponseMapper;
import cn.lunalhx.ai.trigger.http.agent.AgentSseResponder;
import cn.lunalhx.ai.types.enums.ResponseCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class AgentApprovalControllerContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static AgentRuntimeProperties enabledProperties() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setEnabled(true);
        properties.setTotalTimeoutMs(3000L);
        return properties;
    }

    private static ThreadPoolExecutor syncExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    }

    private MockMvc buildMockMvc(AgentLoopService agentLoopService,
                                 ApprovalStore approvalStore) {
        AgentRuntimeProperties properties = enabledProperties();
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        AgentResponseMapper responseMapper = new AgentResponseMapper();
        AgentRequestMapper requestMapper = new AgentRequestMapper(properties, validator);
        AgentSseResponder sseResponder = new AgentSseResponder(properties, syncExecutor(), responseMapper);
        AgentApprovalHttpQueryService approvalHttpQueryService =
                new AgentApprovalHttpQueryService(approvalStore, responseMapper);
        AgentApprovalController controller = new AgentApprovalController(
                requestMapper, sseResponder, agentLoopService, approvalHttpQueryService);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    // ===== 1. POST /approvals/{id}/decide/stream =====

    @Test
    public void decideApproveShouldMapToEnumAndCallService() throws Exception {
        AgentLoopService svc = mock(AgentLoopService.class);
        when(svc.resume(any(), eq(ApprovalDecision.APPROVE), any())).thenReturn(Flux.just(
                AgentEvent.builder().type(AgentEventType.RESUME_STARTED).runId("r").build(),
                AgentEvent.builder().type(AgentEventType.DONE).runId("r").build()));
        MockMvc mvc = buildMockMvc(svc, mock(ApprovalStore.class));
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/approvals/ap-1/decide/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AgentApprovalDecisionRequest("APPROVE", "ok"))))
                .andExpect(status().isOk()).andReturn();
        String content = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn().getResponse().getContentAsString();
        verify(svc).resume(eq("ap-1"), eq(ApprovalDecision.APPROVE), eq("ok"));
        assertTrue(content.contains("event:resume_started"));
        assertTrue(content.contains("event:done"));
    }

    @Test
    public void decideInvalidDecisionShouldReturnErrorWithoutCallingService() throws Exception {
        AgentLoopService svc = mock(AgentLoopService.class);
        MockMvc mvc = buildMockMvc(svc, mock(ApprovalStore.class));
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/approvals/ap-1/decide/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AgentApprovalDecisionRequest("MAYBE", null))))
                .andExpect(status().isOk()).andReturn();
        String content = mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn().getResponse().getContentAsString();
        assertTrue(content.contains("\"code\":\"invalid_request\""));
        verify(svc, never()).resume(any(), any(), any());
    }

    // ===== 2. GET /approvals/{id} =====

    @Test
    public void approvalFoundShouldReturnSuccess() throws Exception {
        ApprovalStore store = mock(ApprovalStore.class);
        PendingApproval approval = PendingApproval.builder()
                .approvalId("ap-1").runId("r-1").requestId("req-1").conversationId("c-1")
                .workspaceDisplayName("ws").tool("write_file")
                .permissionLevel(ToolPermissionLevel.WRITE_CONFIRM)
                .riskReason("r").operationPreview("p")
                .expiresAt(Instant.now().plusSeconds(60)).build();
        when(store.find("ap-1")).thenReturn(Optional.of(approval));
        MockMvc mvc = buildMockMvc(mock(AgentLoopService.class), store);
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/agent/code/approvals/ap-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.approvalId").value("ap-1"))
                .andExpect(jsonPath("$.data.tool").value("write_file"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    public void approvalNotFoundShouldReturnIllegalParameter() throws Exception {
        ApprovalStore store = mock(ApprovalStore.class);
        when(store.find("missing")).thenReturn(Optional.empty());
        MockMvc mvc = buildMockMvc(mock(AgentLoopService.class), store);
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/agent/code/approvals/missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResponseCode.ILLEGAL_PARAMETER.getCode()));
    }
}
