package cn.lunalhx.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Durable Ambiguity Review state for unverifiable Interrupted Tool Calls.
 * User facts are not Tool Results and do not change Tool permission.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AmbiguityReview {

    private boolean active;

    @Builder.Default
    private List<String> facts = new ArrayList<>();
}
