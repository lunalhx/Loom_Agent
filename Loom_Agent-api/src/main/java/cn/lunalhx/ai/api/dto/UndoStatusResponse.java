package cn.lunalhx.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UndoStatusResponse {

    private String runId;
    private String status;
    private boolean canUndo;
    private long snapshotVersion;
    private List<ChangedFileEntry> changedFiles;
    private int changedFileCount;
    private String reasonCode;
    private String reason;
    private String expiresAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChangedFileEntry {
        private String path;
        private String changeType;
    }
}
