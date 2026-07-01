package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.api.dto.AgentApprovalResponse;
import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.types.enums.ResponseCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentApprovalHttpQueryService {

    private final ApprovalStore approvalStore;
    private final AgentResponseMapper responseMapper;

    public Response<AgentApprovalResponse> approval(String approvalId) {
        return approvalStore.find(approvalId)
                .map(approval -> Response.success(responseMapper.toApprovalResponse(approval)))
                .orElseGet(this::approvalNotFound);
    }

    private Response<AgentApprovalResponse> approvalNotFound() {
        return Response.<AgentApprovalResponse>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info("审批不存在或已过期")
                .build();
    }
}
