package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

/**
 * Agent Loop 状态存储与恢复相关依赖。
 *
 * <p>Phase 2 依赖分组：新生产路径只允许通过此 record 传递状态依赖，
 * 不允许依赖为 null。
 *
 * @see AgentLoopRuntimeDependencies
 */
public record AgentLoopStateDependencies(
        ApprovalStore approvalStore,
        AgentWorkspaceResolver workspaceResolver,
        AgentRunRepository runRepository,
        AgentCheckpointRepository checkpointRepository,
        ObjectMapper objectMapper
) {
    public AgentLoopStateDependencies {
        Objects.requireNonNull(approvalStore, "approvalStore must not be null");
        Objects.requireNonNull(workspaceResolver, "workspaceResolver must not be null");
        Objects.requireNonNull(runRepository, "runRepository must not be null");
        Objects.requireNonNull(checkpointRepository, "checkpointRepository must not be null");
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }
}
