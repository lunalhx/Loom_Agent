package cn.lunalhx.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UndoExecuteResponse {

    private String runId;
    private boolean success;
    private String code;
    private String message;
    private int restoredFileCount;
}
