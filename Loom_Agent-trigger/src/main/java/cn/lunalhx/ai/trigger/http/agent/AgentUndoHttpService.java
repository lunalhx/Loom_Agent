package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.api.dto.UndoExecuteRequest;
import cn.lunalhx.ai.api.dto.UndoExecuteResponse;
import cn.lunalhx.ai.api.dto.UndoStatusResponse;
import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.domain.agent.service.undo.WorkspaceUndoService;
import cn.lunalhx.ai.domain.common.CommonErrorCode;
import cn.lunalhx.ai.types.error.ApplicationException;
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
            throw new ApplicationException(CommonErrorCode.INVALID_PARAMETER, "runId 不能为空");
        }

        WorkspaceUndoService.UndoStatusResult result = workspaceUndoService.queryStatus(runId);

        List<UndoStatusResponse.ChangedFileEntry> changedFiles = new ArrayList<>();
        for (WorkspaceUndoService.ChangedFileEntry entry : result.changedFiles()) {
            changedFiles.add(UndoStatusResponse.ChangedFileEntry.builder()
                    .path(entry.path())
                    .changeType(entry.changeType())
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

        return Response.success(data);
    }

    public Response<UndoExecuteResponse> execute(String runId, UndoExecuteRequest request) {
        if (runId == null || runId.isBlank()) {
            throw new ApplicationException(CommonErrorCode.INVALID_PARAMETER, "runId 不能为空");
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
            return Response.success(data);
        }

        return Response.failure(CommonErrorCode.INVALID_PARAMETER, result.message());
    }
}
