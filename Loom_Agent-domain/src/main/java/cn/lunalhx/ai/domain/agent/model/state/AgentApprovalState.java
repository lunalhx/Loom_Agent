package cn.lunalhx.ai.domain.agent.model.state;

/**
 * Mutable approval state with behavior methods for complete state transitions.
 */
public final class AgentApprovalState {

    private boolean unsafeResumeRequired;
    private String pendingApprovalId;
    private String approvedTool;
    private String approvedPolicyFingerprint;
    private boolean approvalExpired;
    private String expiredApprovalId;

    // -- getters --

    public boolean unsafeResumeRequired() { return unsafeResumeRequired; }
    public String pendingApprovalId() { return pendingApprovalId; }
    public String approvedTool() { return approvedTool; }
    public String approvedPolicyFingerprint() { return approvedPolicyFingerprint; }
    public boolean approvalExpired() { return approvalExpired; }
    public String expiredApprovalId() { return expiredApprovalId; }

    // -- package-private mutators --

    public void setUnsafeResumeRequired(boolean v) { this.unsafeResumeRequired = v; }
    public void setPendingApprovalId(String v) { this.pendingApprovalId = v; }
    public void setApprovedTool(String v) { this.approvedTool = v; }
    public void setApprovedPolicyFingerprint(String v) { this.approvedPolicyFingerprint = v; }
    public void setApprovalExpired(boolean v) { this.approvalExpired = v; }
    public void setExpiredApprovalId(String v) { this.expiredApprovalId = v; }

    // -- behavior methods --

    public void requestApproval(String approvalId) {
        this.pendingApprovalId = approvalId;
        this.approvalExpired = false;
        this.expiredApprovalId = null;
    }

    public void approve(String tool, String policyFingerprint) {
        this.approvedTool = tool;
        this.approvedPolicyFingerprint = policyFingerprint;
        this.pendingApprovalId = null;
    }

    public void reject() {
        this.approvedTool = null;
        this.approvedPolicyFingerprint = null;
        this.pendingApprovalId = null;
    }

    public void expire(String approvalId) {
        this.pendingApprovalId = null;
        this.approvalExpired = true;
        this.expiredApprovalId = approvalId;
    }

    public void beginRiskyDispatch() {
        this.unsafeResumeRequired = true;
    }

    public void finishDispatch() {
        this.unsafeResumeRequired = false;
    }

    public void clearConsumedApproval() {
        this.pendingApprovalId = null;
        this.approvedTool = null;
        this.approvedPolicyFingerprint = null;
        this.approvalExpired = false;
        this.expiredApprovalId = null;
    }
}
