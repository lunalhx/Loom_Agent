package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.api.dto.UndoExecuteRequest;
import cn.lunalhx.ai.api.dto.UndoExecuteResponse;
import cn.lunalhx.ai.api.dto.UndoStatusResponse;
import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.domain.agent.service.WorkspaceUndoService;
import cn.lunalhx.ai.types.enums.ResponseCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentUndoHttpService {

    private final WorkspaceUndoService workspaceUndoService;

    public Response<UndoStatusResponse> query(String runId) {
        if (runId == null || runId.isBlank()) {
            return Response.<UndoStatusResponse>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("runId 不能为空")
                    .build();
        }

        WorkspaceUndoService.UndoStatusResult result = workspaceUndoService.queryStatus(runId);

        List<UndoStatusResponse.ChangedFileEntry> changedFiles = new ArrayList<>();
        for (String path : result.changedFiles()) {
            changedFiles.add(UndoStatusResponse.ChangedFileEntry.builder()
                    .path(path)
                    .changeType("MODIFIED")
                    .build());
        }

        UndoStatusResponse data = UndoStatusResponse.builder()
                .runId(result.runId())
                .status(result.status())
                .canUndo(result.canUndo())
                .snapshotVersion(result.snapshotVersion())
                .changedFiles(changedFiles)
                .changedFileCount(result.changedFileCount())
                .reasonCode(result.reasonCode())
                .reason(result.reasonCode())
                .expiresAt(result.expiresAt())
                .build();

        return Response.<UndoStatusResponse>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }

    public Response<UndoExecuteResponse> execute(String runId, UndoExecuteRequest request) {
        if (runId == null || runId.isBlank()) {
            return Response.<UndoExecuteResponse>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("runId 不能为空")
                    .build();
        }

        WorkspaceUndoService.UndoExecuteResult result =
                workspaceUndoService.executeUndo(runId, request.getExpectedSnapshotVersion());

        UndoExecuteResponse data = UndoExecuteResponse.builder()
                .runId(result.runId())
                .success(result.success())
                .code(result.code())
                .message(result.message())
                .restoredFileCount(result.restoredFileCount())
                .build();

        if (result.success()) {
            return Response.<UndoExecuteResponse>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        }

        return Response.<UndoExecuteResponse>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info(result.message())
                .data(data)
                .build();
    }
}
