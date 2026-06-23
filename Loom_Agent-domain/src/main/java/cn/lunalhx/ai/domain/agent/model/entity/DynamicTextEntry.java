package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.DynamicTextRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DynamicTextEntry {

    private int step;
    private DynamicTextRole role;
    private String sourceNode;
    private String title;
    private String tool;
    private String input;
    private String content;
    private String entryId;
    private String artifactId;
    private Integer originalChars;
    private Integer renderChars;
    private Boolean compacted;
    private String summary;

}
