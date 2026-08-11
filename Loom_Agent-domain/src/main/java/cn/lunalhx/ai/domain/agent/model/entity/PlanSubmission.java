package cn.lunalhx.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent-provided content for the first Plan revision.
 *
 * <p>Runtime-owned Plan identity, revision, timestamps, digest and evidence
 * basis are deliberately not part of this value.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanSubmission {

    private String title;
    private String body;
    private List<String> dependencies;
}
