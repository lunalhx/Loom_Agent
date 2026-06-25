package cn.lunalhx.ai.domain.agent.service;

record SubAgentPlanResult(
        SubAgentDispatchPlan plan,
        String errorCode,
        String errorMessage) {

    static SubAgentPlanResult success(SubAgentDispatchPlan plan) {
        return new SubAgentPlanResult(plan, null, null);
    }

    static SubAgentPlanResult error(String errorCode, String errorMessage) {
        return new SubAgentPlanResult(null, errorCode, errorMessage);
    }
}
