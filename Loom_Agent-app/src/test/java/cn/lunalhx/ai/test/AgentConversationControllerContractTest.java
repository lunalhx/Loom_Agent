package cn.lunalhx.ai.test;

import cn.lunalhx.ai.api.dto.ConversationDeletionResponse;
import cn.lunalhx.ai.api.dto.ConversationSummaryResponse;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.service.conversation.ConversationDeletionService;
import cn.lunalhx.ai.trigger.http.AgentConversationController;
import cn.lunalhx.ai.trigger.http.agent.AgentConversationHttpService;
import cn.lunalhx.ai.types.enums.ResponseCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class AgentConversationControllerContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc buildMockMvc(AgentConversationHttpService conversationHttpService) {
        AgentConversationController controller = new AgentConversationController(conversationHttpService);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private AgentConversationHttpService mockConversationService(
            List<ConversationSummaryResponse> conversations,
            ConversationDeletionService.DeletionRequestResult deletionResult,
            Optional<ConversationDeletionService.DeletionRequestResult> deletionStatus) {
        AgentRunRepository runRepo = mock(AgentRunRepository.class);
        ConversationDeletionService deletionService = mock(ConversationDeletionService.class);
        AgentConversationHttpService service =
                new AgentConversationHttpService(runRepo, deletionService);

        AgentConversationHttpService spy = mock(AgentConversationHttpService.class);
        when(spy.listConversations()).thenReturn(conversations);
        if (deletionResult != null) {
            when(spy.requestDeletion(any())).thenReturn(deletionResult);
        }
        if (deletionStatus != null) {
            when(spy.getDeletionStatus(any())).thenReturn(deletionStatus);
        }
        return spy;
    }

    @Test
    public void listConversationsShouldReturnSuccess() throws Exception {
        List<ConversationSummaryResponse> list = List.of(
                ConversationSummaryResponse.builder()
                        .conversationId("c-1").title("test").runCount(3).workspace("/ws").build());
        AgentConversationHttpService svc = mockConversationService(list, null, null);
        MockMvc mvc = buildMockMvc(svc);
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/agent/code/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data[0].conversationId").value("c-1"))
                .andExpect(jsonPath("$.data[0].title").value("test"))
                .andExpect(jsonPath("$.data[0].runCount").value(3));
    }

    @Test
    public void deleteConversationAlreadyCompletedShouldReturnOk() throws Exception {
        ConversationDeletionService.DeletionRequestResult result =
                new ConversationDeletionService.DeletionRequestResult(
                        "c-1", "COMPLETED", "2024-01-01T00:00:00Z", "2024-01-01T01:00:00Z", 0, null, false, false);
        AgentConversationHttpService svc = mockConversationService(null, result, null);
        MockMvc mvc = buildMockMvc(svc);
        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/agent/code/conversations/c-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.conversationId").value("c-1"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    public void deletionStatusNotFoundShouldReturnNullData() throws Exception {
        AgentConversationHttpService svc = mockConversationService(null, null, Optional.empty());
        MockMvc mvc = buildMockMvc(svc);
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/agent/code/conversations/missing/deletion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.info").value("not found"));
    }

    @Test
    public void deletionStatusCompletedShouldReturnSuccess() throws Exception {
        ConversationDeletionService.DeletionRequestResult result =
                new ConversationDeletionService.DeletionRequestResult(
                        "c-1", "COMPLETED", "2024-01-01T00:00:00Z", "2024-01-01T01:00:00Z", 0, null, false, false);
        AgentConversationHttpService svc = mockConversationService(null, null, Optional.of(result));
        MockMvc mvc = buildMockMvc(svc);
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/agent/code/conversations/c-1/deletion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.conversationId").value("c-1"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    public void deleteConversationNotFoundShouldThrowNotFound() throws Exception {
        AgentConversationHttpService svc = mockConversationService(null, null, null);
        when(svc.requestDeletion("missing"))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "conversation not found"));
        MockMvc mvc = buildMockMvc(svc);
        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/agent/code/conversations/missing"))
                .andExpect(status().isNotFound());
    }
}
