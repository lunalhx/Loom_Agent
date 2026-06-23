package cn.lunalhx.ai.domain.agent.model.entity.context;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class ContextCompactResult {

    boolean compacted;
    int beforeEstimatedTokens;
    int afterEstimatedTokens;
    int artifactCount;
    List<String> strategies;

    public static ContextCompactResult none(int estimatedTokens) {
        return ContextCompactResult.builder()
                .compacted(false)
                .beforeEstimatedTokens(estimatedTokens)
                .afterEstimatedTokens(estimatedTokens)
                .artifactCount(0)
                .strategies(List.of())
                .build();
    }

}
