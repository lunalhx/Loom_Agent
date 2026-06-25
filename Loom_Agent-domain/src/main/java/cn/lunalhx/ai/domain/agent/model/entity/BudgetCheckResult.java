package cn.lunalhx.ai.domain.agent.model.entity;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class BudgetCheckResult {

    private boolean allowed;
    private long usedTokens;
    private long estimatedInputTokens;
    private long reservedOutputTokens;
    private long maxTotalTokens;
    private String reason;
    private BigDecimal estimatedRequestCost;
    private BigDecimal remainingCost;

    public static BudgetCheckResult allowed(long usedTokens, long estimatedInputTokens, long reservedOutputTokens, long maxTotalTokens) {
        return BudgetCheckResult.builder()
                .allowed(true)
                .usedTokens(usedTokens)
                .estimatedInputTokens(estimatedInputTokens)
                .reservedOutputTokens(reservedOutputTokens)
                .maxTotalTokens(maxTotalTokens)
                .build();
    }

    public static BudgetCheckResult blocked(long usedTokens,
                                            long estimatedInputTokens,
                                            long reservedOutputTokens,
                                            long maxTotalTokens) {
        return BudgetCheckResult.builder()
                .allowed(false)
                .usedTokens(usedTokens)
                .estimatedInputTokens(estimatedInputTokens)
                .reservedOutputTokens(reservedOutputTokens)
                .maxTotalTokens(maxTotalTokens)
                .reason("used_tokens + estimated_input_tokens + reserved_output_tokens > max_total_tokens")
                .build();
    }

}
