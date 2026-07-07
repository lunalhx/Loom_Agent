package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A run-scoped approval grant that allows bypassing approval for matching commands.
 * v1 supports exact command match only.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalGrant {

    /**
     * The exact command string that was approved.
     */
    private String pattern;

    /**
     * When the grant was issued.
     */
    private Instant grantedAt;

    /**
     * Matches if the given command equals this grant's pattern (exact match).
     */
    public boolean matches(String command) {
        return pattern != null && pattern.equals(command);
    }
}
