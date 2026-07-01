package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.api.dto.AgentApprovalResponse;
import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.types.enums.ResponseCode;
import org.junit.Before;
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AgentApprovalHttpQueryServiceTest {

    private ApprovalStore approvalStore;
    private AgentApprovalHttpQueryService queryService;

    @Before
    public void setUp() {
        approvalStore = mock(ApprovalStore.class);
        AgentResponseMapper responseMapper = new AgentResponseMapper();
        queryService = new AgentApprovalHttpQueryService(approvalStore, responseMapper);
    }

    @Test
    public void approvalFoundShouldReturnSuccess() {
        PendingApproval approval = PendingApproval.builder()
                .approvalId("ap-1").runId("r-1").requestId("req-1").conversationId("c-1")
                .build();
        when(approvalStore.find("ap-1")).thenReturn(Optional.of(approval));
        Response<AgentApprovalResponse> result = queryService.approval("ap-1");
        assertEquals(ResponseCode.SUCCESS.getCode(), result.getCode());
        assertEquals("ap-1", result.getData().getApprovalId());
        assertEquals("PENDING", result.getData().getStatus());
    }

    @Test
    public void approvalNotFoundShouldReturnIllegalParameter() {
        when(approvalStore.find("missing")).thenReturn(Optional.empty());
        Response<AgentApprovalResponse> result = queryService.approval("missing");
        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), result.getCode());
        assertEquals("审批不存在或已过期", result.getInfo());
    }
}
