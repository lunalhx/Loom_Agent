package cn.lunalhx.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Write-ahead record for the cross-artifact Plan Submission transaction.
 * It is not part of the visible Plan aggregate until the terminal Run is
 * durable and the transaction is committed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanSubmissionTransaction {

    private String transactionId;
    private String runId;
    private long expectedPlanStateVersion;
    private Plan plan;
}
