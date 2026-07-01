package cn.lunalhx.ai.domain.agent.service.subagent;

import cn.lunalhx.ai.domain.agent.model.entity.SubAgentResult;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentTask;

import java.util.concurrent.CompletableFuture;

record SubAgentRunHandle(
        SubAgentTask task,
        int ordinal,
        String childRunId,
        CompletableFuture<SubAgentResult> future,
        long startedAt) {
}
