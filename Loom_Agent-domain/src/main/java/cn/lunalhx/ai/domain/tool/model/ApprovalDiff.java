package cn.lunalhx.ai.domain.tool.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalDiff {

    private String format;
    private String path;
    private String oldText;
    private String newText;
    private String unifiedDiff;
    private Boolean editable;
    private List<DiffHunk> hunks;
    private DiffStats stats;

}
