package cn.lunalhx.ai.domain.agent.service;

record ContextStrategyResult(
        boolean changed,
        String strategy,
        int retainedEntryCount,
        String transcriptArtifactId) {

    static ContextStrategyResult unchanged() {
        return new ContextStrategyResult(false, null, 0, null);
    }
}
