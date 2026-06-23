package cn.lunalhx.ai.domain.agent.model.entity.context;

import cn.lunalhx.ai.domain.agent.model.valobj.context.ContextArtifactKind;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContextArtifact {

    private String artifactId;
    private String runId;
    private String rootRunId;
    private String conversationId;
    private ContextArtifactKind kind;
    private String storageUri;
    private String preview;
    private String sha256;
    private Integer originalChars;
    private Integer retainedChars;
    private Instant createdAt;

}
