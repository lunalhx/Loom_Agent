package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.agent.adapter.port.PersistentApprovalStore;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import cn.lunalhx.ai.infrastructure.dao.AgentPendingApprovalDao;
import cn.lunalhx.ai.infrastructure.dao.po.AgentPendingApprovalPO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

public class MybatisApprovalStore implements PersistentApprovalStore {

    private final AgentPendingApprovalDao approvalDao;
    private final ObjectMapper objectMapper;

    public MybatisApprovalStore(AgentPendingApprovalDao approvalDao, ObjectMapper objectMapper) {
        this.approvalDao = approvalDao;
        this.objectMapper = objectMapper;
    }

    @Override
    public PendingApproval save(PendingApproval approval) {
        approvalDao.upsert(toPo(approval));
        return approval;
    }

    @Override
    public Optional<PendingApproval> find(String approvalId) {
        AgentPendingApprovalPO po = approvalDao.selectByApprovalId(approvalId);
        if (po == null || Integer.valueOf(1).equals(po.getConsumed())) {
            return Optional.empty();
        }
        PendingApproval approval = toEntity(po);
        if (approval.expired(Instant.now())) {
            approvalDao.markConsumed(approvalId);
            return Optional.empty();
        }
        return Optional.of(approval);
    }

    @Override
    public Optional<PendingApproval> consume(String approvalId) {
        Optional<PendingApproval> approval = find(approvalId);
        approval.ifPresent(value -> approvalDao.markConsumed(approvalId));
        return approval;
    }

    private AgentPendingApprovalPO toPo(PendingApproval approval) {
        AgentPendingApprovalPO po = new AgentPendingApprovalPO();
        po.setApprovalId(approval.getApprovalId());
        po.setRunId(approval.getRunId());
        po.setRequestId(approval.getRequestId());
        po.setConversationId(approval.getConversationId());
        po.setResolvedWorkspace(approval.getResolvedWorkspace() == null ? null : approval.getResolvedWorkspace().toString());
        po.setWorkspaceDisplayName(approval.getWorkspaceDisplayName());
        po.setTool(approval.getTool());
        po.setInputJson(writeJson(approval.getInput()));
        po.setPermissionLevel(approval.getPermissionLevel() == null ? null : approval.getPermissionLevel().name());
        po.setRiskReason(approval.getRiskReason());
        po.setOperationPreview(approval.getOperationPreview());
        po.setContextJson(writeJson(approval.getContext() == null ? null : AgentContextSnapshot.from(approval.getContext())));
        po.setCreatedAt(toLocalDateTime(approval.getCreatedAt()));
        po.setExpiresAt(toLocalDateTime(approval.getExpiresAt()));
        po.setConsumed(0);
        return po;
    }

    private PendingApproval toEntity(AgentPendingApprovalPO po) {
        AgentContextSnapshot snapshot = readJson(po.getContextJson(), AgentContextSnapshot.class);
        return PendingApproval.builder()
                .approvalId(po.getApprovalId())
                .runId(po.getRunId())
                .requestId(po.getRequestId())
                .conversationId(po.getConversationId())
                .resolvedWorkspace(po.getResolvedWorkspace() == null ? null : Path.of(po.getResolvedWorkspace()))
                .workspaceDisplayName(po.getWorkspaceDisplayName())
                .tool(po.getTool())
                .input(readJson(po.getInputJson(), new TypeReference<Map<String, Object>>() {
                }))
                .permissionLevel(po.getPermissionLevel() == null ? null : ToolPermissionLevel.valueOf(po.getPermissionLevel()))
                .riskReason(po.getRiskReason())
                .operationPreview(po.getOperationPreview())
                .createdAt(toInstant(po.getCreatedAt()))
                .expiresAt(toInstant(po.getExpiresAt()))
                .context(snapshot == null ? null : snapshot.restore())
                .build();
    }

    private String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化审批状态失败", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return json == null ? null : objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("反序列化审批状态失败", e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return json == null ? null : objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("反序列化审批输入失败", e);
        }
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant();
    }

}
