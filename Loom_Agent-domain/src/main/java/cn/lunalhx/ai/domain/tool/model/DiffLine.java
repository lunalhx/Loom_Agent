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
public class DiffLine {

    private String type;
    private Integer oldLineNumber;
    private Integer newLineNumber;
    private String text;
    private Integer pairId;
    private Integer foldedCount;
    private List<InlineDiffPart> inlineDiff;

}
