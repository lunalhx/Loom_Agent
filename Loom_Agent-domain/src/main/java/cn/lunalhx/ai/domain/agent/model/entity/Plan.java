package cn.lunalhx.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Durable Plan aggregate owned by an AgentSession. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Plan {

    private String planId;
    private Instant createdAt;
    private Instant updatedAt;
    @Builder.Default
    private List<PlanRevision> revisions = new ArrayList<>();

    public PlanRevision currentRevision() {
        if (revisions == null || revisions.isEmpty()) {
            return null;
        }
        return revisions.get(revisions.size() - 1);
    }
}
