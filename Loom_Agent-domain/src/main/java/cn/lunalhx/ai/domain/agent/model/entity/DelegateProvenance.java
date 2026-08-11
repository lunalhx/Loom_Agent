package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Safe identity and boundary metadata for one completed child run. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DelegateProvenance {

    private String runId;
    private String parentRunId;
    private String rootRunId;
    private String sessionId;
    private String workspaceRoot;
    private CollaborationMode modeSnapshot;
    private Integer depth;
}
