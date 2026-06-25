package cn.lunalhx.ai.domain.agent.service;

record ContextCompactionCommand(
        int targetTokens,
        int keepRecentEntries,
        long deadlineEpochMs,
        ContextCompactionMode mode) {

    enum ContextCompactionMode {
        AUTO,
        REACTIVE,
        DEEP
    }
}
