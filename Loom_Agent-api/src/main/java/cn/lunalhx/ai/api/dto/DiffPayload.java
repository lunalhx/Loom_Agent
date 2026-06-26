package cn.lunalhx.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiffPayload {

    private String format;
    private String path;
    private String oldText;
    private String newText;
    private String unifiedDiff;
    private Boolean editable;

}
