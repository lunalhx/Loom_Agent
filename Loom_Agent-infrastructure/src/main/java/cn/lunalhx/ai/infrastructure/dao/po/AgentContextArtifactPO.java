package cn.lunalhx.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.Instant;

@Data
public class AgentContextArtifactPO {

    private Long id;
    private String artifactId;
    private String runId;
    private String rootRunId;
    private String conversationId;
    private String kind;
    private String storageUri;
    private String preview;
    private String sha256;
    private Integer originalChars;
    private Integer retainedChars;
    private Instant createTime;

}
