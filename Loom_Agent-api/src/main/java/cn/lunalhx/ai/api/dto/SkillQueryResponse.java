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
public class SkillQueryResponse {

    private String name;
    private String description;
    private String source;
    private String compatibility;
    private String trustState;
    private List<String> diagnostics;

}
