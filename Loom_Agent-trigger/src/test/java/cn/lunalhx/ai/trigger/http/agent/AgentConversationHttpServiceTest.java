package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.api.dto.ConversationDeletionResponse;
import cn.lunalhx.ai.api.dto.ConversationSummaryResponse;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationSummary;
import cn.lunalhx.ai.domain.agent.service.conversation.ConversationDeletionService;
import cn.lunalhx.ai.domain.common.CommonErrorCode;
import cn.lunalhx.ai.types.error.ApplicationException;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AgentConversationHttpServiceTest {

    private AgentRunRepository runRepository;
    private ConversationDeletionService deletionService;
    private AgentConversationHttpService service;

    @Before
    public void setUp() {
        runRepository = mock(AgentRunRepository.class);
        deletionService = mock(ConversationDeletionService.class);
        service = new AgentConversationHttpService(runRepository, deletionService);
    }

    @Test
    public void listConversationsShouldMapCorrectly() {
        ConversationSummary summary = ConversationSummary.builder()
                .conversationId("c-1").title("test").runCount(3).workspace("/ws").build();
        when(runRepository.listConversationSummaries()).thenReturn(List.of(summary));

        List<ConversationSummaryResponse> result = service.listConversations();
        assertEquals(1, result.size());
        assertEquals("c-1", result.get(0).getConversationId());
        assertEquals("test", result.get(0).getTitle());
        assertEquals(3, result.get(0).getRunCount());
        assertEquals("/ws", result.get(0).getWorkspace());
    }

    @Test
    public void requestDeletionSuccessShouldReturnResult() {
        ConversationDeletionService.DeletionRequestResult result =
                new ConversationDeletionService.DeletionRequestResult(
                        "c-1", "REQUESTED", "2024-01-01T00:00:00Z", null, 0, null, false, false);
        when(deletionService.requestDeletion("c-1")).thenReturn(result);

        ConversationDeletionService.DeletionRequestResult returned = service.requestDeletion("c-1");
        assertEquals("c-1", returned.conversationId());
        assertEquals("REQUESTED", returned.status());
    }

    @Test
    public void requestDeletionInvalidShouldThrowApplicationException() {
        ConversationDeletionService.DeletionRequestResult result =
                new ConversationDeletionService.DeletionRequestResult(
                        null, null, null, null, 0, "invalid id", false, true);
        when(deletionService.requestDeletion("bad")).thenReturn(result);

        try {
            service.requestDeletion("bad");
            fail("Expected ApplicationException");
        } catch (ApplicationException e) {
            assertEquals(CommonErrorCode.INVALID_PARAMETER.code(), e.code());
            assertEquals("invalid id", e.getMessage());
        }
    }

    @Test
    public void requestDeletionNotFoundShouldThrowApplicationException() {
        ConversationDeletionService.DeletionRequestResult result =
                new ConversationDeletionService.DeletionRequestResult(
                        null, null, null, null, 0, null, true, false);
        when(deletionService.requestDeletion("missing")).thenReturn(result);

        try {
            service.requestDeletion("missing");
            fail("Expected ApplicationException");
        } catch (ApplicationException e) {
            assertEquals(CommonErrorCode.INVALID_REQUEST.code(), e.code());
        }
    }

    @Test
    public void getDeletionStatusFoundShouldReturnResult() {
        ConversationDeletionService.DeletionRequestResult result =
                new ConversationDeletionService.DeletionRequestResult(
                        "c-1", "COMPLETED", "2024-01-01T00:00:00Z", "2024-01-01T01:00:00Z", 0, null, false, false);
        when(deletionService.getDeletionStatus("c-1")).thenReturn(Optional.of(result));

        Optional<ConversationDeletionService.DeletionRequestResult> returned =
                service.getDeletionStatus("c-1");
        assertTrue(returned.isPresent());
        assertEquals("COMPLETED", returned.get().status());
    }

    @Test
    public void getDeletionStatusNotFoundShouldReturnEmpty() {
        when(deletionService.getDeletionStatus("missing")).thenReturn(Optional.empty());

        Optional<ConversationDeletionService.DeletionRequestResult> returned =
                service.getDeletionStatus("missing");
        assertTrue(returned.isEmpty());
    }

}
