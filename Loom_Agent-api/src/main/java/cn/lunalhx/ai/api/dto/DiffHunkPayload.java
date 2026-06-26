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
public class DiffHunkPayload {

    private Integer oldStart;
    private Integer oldLines;
    private Integer newStart;
    private Integer newLines;
    private List<DiffLinePayload> lines;

}
