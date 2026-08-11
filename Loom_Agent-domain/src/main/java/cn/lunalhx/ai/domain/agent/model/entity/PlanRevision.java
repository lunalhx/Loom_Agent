package cn.lunalhx.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Runtime-owned immutable content and evidence basis for one Plan revision. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanRevision {

    private Integer revision;
    private String title;
    private String body;
    @Builder.Default
    private List<String> dependencies = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;
    private String contentDigest;
    @Builder.Default
    private List<EvidenceReceipt> planBasis = new ArrayList<>();
}
