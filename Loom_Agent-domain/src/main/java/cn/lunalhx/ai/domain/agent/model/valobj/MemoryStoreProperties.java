package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.Data;

@Data
public class MemoryStoreProperties {

    private long ttlSeconds = 3600;
    private int maxRuns = 1000;
    private int maxApprovals = 1000;
    private int maxTraceRuns = 1000;
    private int maxTraceEventsPerRun = 1000;
    private int maxCheckpointRuns = 1000;
    private int maxCheckpointsPerRun = 5;
    private int maxContextArtifacts = 2000;
    private int maxContextBlobs = 2000;
    private int maxBudgetStates = 1000;
    private int maxSubAgentInboxes = 1000;
    private int maxSubAgentMessagesPerRun = 10;
}
