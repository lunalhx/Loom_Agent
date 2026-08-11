package cn.lunalhx.ai.domain.agent.model.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of the Plan revision that authorizes a Build Run.
 *
 * <p>The document is retained with its identities so a restored Run can keep
 * receiving the same constraints even after the Plan aggregate advances.</p>
 */
public final class PlanBinding {

    private final String planId;
    private final Integer revision;
    private final String planDocumentDigest;
    private final String planBasisIdentity;
    private final String title;
    private final String body;
    private final List<String> dependencies;
    private final boolean issuedByPlanHandoff;

    @JsonCreator
    public PlanBinding(@JsonProperty("planId") String planId,
                       @JsonProperty("revision") Integer revision,
                       @JsonProperty("planDocumentDigest") String planDocumentDigest,
                       @JsonProperty("planBasisIdentity") String planBasisIdentity,
                       @JsonProperty("title") String title,
                       @JsonProperty("body") String body,
                       @JsonProperty("dependencies") List<String> dependencies,
                       @JsonProperty("issuedByPlanHandoff") Boolean issuedByPlanHandoff) {
        this(planId, revision, planDocumentDigest, planBasisIdentity, title, body,
                dependencies, Boolean.TRUE.equals(issuedByPlanHandoff));
    }

    public PlanBinding(String planId, Integer revision, String planDocumentDigest,
                       String planBasisIdentity, String title, String body,
                       List<String> dependencies) {
        this(planId, revision, planDocumentDigest, planBasisIdentity, title, body,
                dependencies, false);
    }

    private PlanBinding(String planId, Integer revision, String planDocumentDigest,
                        String planBasisIdentity, String title, String body,
                        List<String> dependencies, boolean issuedByPlanHandoff) {
        this.planId = Objects.requireNonNull(planId, "planId must not be null");
        this.revision = Objects.requireNonNull(revision, "revision must not be null");
        this.planDocumentDigest = Objects.requireNonNull(planDocumentDigest,
                "planDocumentDigest must not be null");
        this.planBasisIdentity = Objects.requireNonNull(planBasisIdentity,
                "planBasisIdentity must not be null");
        this.title = Objects.requireNonNull(title, "title must not be null");
        this.body = Objects.requireNonNull(body, "body must not be null");
        this.dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        this.issuedByPlanHandoff = issuedByPlanHandoff;
    }

    /** Creates the only binding form that can authorize Plan Deviation. */
    public static PlanBinding fromHandoff(String planId, Integer revision,
                                          String planDocumentDigest,
                                          String planBasisIdentity, String title,
                                          String body, List<String> dependencies) {
        return new PlanBinding(planId, revision, planDocumentDigest, planBasisIdentity,
                title, body, dependencies, true);
    }

    public String getPlanId() {
        return planId;
    }

    public Integer getRevision() {
        return revision;
    }

    public String getPlanDocumentDigest() {
        return planDocumentDigest;
    }

    public String getPlanBasisIdentity() {
        return planBasisIdentity;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public List<String> getDependencies() {
        return dependencies;
    }

    public boolean isIssuedByPlanHandoff() {
        return issuedByPlanHandoff;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PlanBinding that)) {
            return false;
        }
        return Objects.equals(planId, that.planId)
                && Objects.equals(revision, that.revision)
                && Objects.equals(planDocumentDigest, that.planDocumentDigest)
                && Objects.equals(planBasisIdentity, that.planBasisIdentity)
                && Objects.equals(title, that.title)
                && Objects.equals(body, that.body)
                && Objects.equals(dependencies, that.dependencies)
                && issuedByPlanHandoff == that.issuedByPlanHandoff;
    }

    @Override
    public int hashCode() {
        return Objects.hash(planId, revision, planDocumentDigest, planBasisIdentity,
                title, body, dependencies, issuedByPlanHandoff);
    }

    /** Complete constraints sent as the current request of the bound Run. */
    public String authoritativePrompt() {
        return "Implement this exact Plan revision. The Plan document is authoritative for "
                + "the objective, scope, architectural decisions, and validation requirements. "
                + "Equivalent implementation detail and ordering remain your choice.\n\n"
                + "Plan identity: " + planId + " revision " + revision + "\n"
                + "Plan title: " + title + "\n"
                + "Plan dependencies: " + dependencies + "\n"
                + "Plan document:\n" + body;
    }
}
