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
public class DiffHunk {

    private Integer oldStart;
    private Integer oldLines;
    private Integer newStart;
    private Integer newLines;
    private List<DiffLine> lines;

}
