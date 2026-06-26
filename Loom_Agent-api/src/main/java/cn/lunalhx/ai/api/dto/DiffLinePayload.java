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
public class DiffLinePayload {

    private String type;
    private Integer oldLineNumber;
    private Integer newLineNumber;
    private String text;
    private Integer pairId;
    private Integer foldedCount;
    private List<InlineDiffPartPayload> inlineDiff;

}
