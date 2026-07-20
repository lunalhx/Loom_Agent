package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.Data;

@Data
public class SkillProperties {
    private Boolean enabled = true;
    private String userDir;
    private String projectDir = ".agents/skills";
    private Integer catalogMaxChars = 8000;
    private Integer maxResourceFiles = 256;
    private Long maxResourceBytes = 10_485_760L;
    private Long maxSnapshotBytes = 52_428_800L;
}
