package cn.lunalhx.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Durable fenced ownership of a non-terminal Run. A healthy owner cannot be
 * taken over; an expired or released fence cannot write or advance the Run.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttemptLease {

    private String runId;
    private String attemptId;
    private String fence;
    private long heartbeatEpochMilli;
    private boolean released;
}
