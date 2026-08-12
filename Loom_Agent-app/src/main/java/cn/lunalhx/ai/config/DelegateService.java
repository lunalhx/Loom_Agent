package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.DelegateRunner;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.DelegateProvenance;
import cn.lunalhx.ai.domain.agent.model.entity.DelegateRequest;
import cn.lunalhx.ai.domain.agent.model.entity.DelegateResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopFactory;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spawns one bounded child loop from a Runtime-created {@link DelegateRequest}.
 * The child receives only the intersection of the parent boundary and the
 * delegate's read-only policy; it never shares the parent context or session
 * ledger.
 */
@Component
public class DelegateService implements DelegateRunner {

    private static final int MAX_SAFE_OUTCOME_CHARS = 4000;
    private static final Set<String> READ_ONLY_TOOLS = Set.of("list_files", "read_file", "search");

    private final AgentLoopFactory loopFactory;
    private final ObjectProvider<ToolRegistry> toolRegistryProvider;
    private final AgentRunRepository runRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    public DelegateService(AgentLoopFactory loopFactory,
                           ObjectProvider<ToolRegistry> toolRegistryProvider,
                           AgentRunRepository runRepository) {
        this.loopFactory = loopFactory;
        this.toolRegistryProvider = toolRegistryProvider;
        this.runRepository = runRepository;
    }

    @Override
    public DelegateResult delegate(DelegateRequest request) {
        Objects.requireNonNull(request, "delegate request must not be null");
        String validationFailure = validate(request);
        if (validationFailure != null) {
            return failed(request, validationFailure);
        }

        String childRunId = "delegate_" + UUID.randomUUID();
        AgentQuestion question = AgentQuestion.builder()
                .runId(childRunId)
                .question(request.getTask())
                .parentRunId(request.getParentRunId())
                .rootRunId(request.getRootRunId())
                .sessionId(request.getSessionId())
                .conversationId(request.getConversationId())
                .workspace(request.getWorkspaceRoot())
                .agentDepth(request.getParentDepth() + 1)
                .maxSteps(request.getChildMaxSteps())
                .maxAttempts(request.getChildMaxAttempts())
                .approvalPolicy("never")
                .inheritedPermissionPolicySnapshot(request.getPermissionPolicySnapshot())
                .inheritedSecurityScope(request.getSecurityScope())
                .collaborationMode(request.getModeSnapshot())
                .planBinding(request.getPlanBinding())
                .allowedTools(request.getAllowedTools())
                .build();

        AtomicReference<String> answer = new AtomicReference<>("");
        AtomicReference<String> error = new AtomicReference<>("");
        try {
            Flux<AgentEvent> events = loopFactory
                    .createStandalone(readOnlyToolRegistry(request), Runnable::run)
                    .ask(question);
            events.doOnNext(event -> {
                if (event.getType() == AgentEventType.ANSWER && event.getAnswer() != null) {
                    answer.set(event.getAnswer());
                }
                if (event.getType() == AgentEventType.ERROR && event.getMessage() != null) {
                    error.set(event.getMessage());
                }
            }).blockLast();
        } catch (Exception e) {
            return failed(request, safeText(e.getMessage(), "delegate child failed"));
        }

        AgentRun child = runRepository.find(childRunId).orElse(null);
        AgentRunStatus status = child == null || child.getStatus() == null
                ? AgentRunStatus.FAILED : child.getStatus();
        String outcome = child == null ? error.get() : child.getFinalAnswer();
        if (outcome == null || outcome.isBlank()) {
            outcome = error.get();
        }
        if (outcome == null || outcome.isBlank()) {
            outcome = status == AgentRunStatus.COMPLETED ? "(empty)" : "delegate child did not complete";
        }

        DelegateProvenance provenance = provenance(request, childRunId, child);
        List<cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt> receipts =
                status == AgentRunStatus.COMPLETED && child != null && child.getEvidenceReceipts() != null
                        ? child.getEvidenceReceipts().stream()
                        .filter(receipt -> receipt != null && receipt.isRevalidatable())
                        .toList()
                        : List.of();
        return DelegateResult.builder()
                .safeOutcome(safeText(outcome, "delegate child completed"))
                .status(status)
                .provenance(provenance)
                .evidenceReceipts(receipts)
                .errorCode(status == AgentRunStatus.COMPLETED ? null : "delegate_incomplete")
                .build();
    }

    private String validate(DelegateRequest request) {
        if (request.getTask() == null || request.getTask().isBlank()) {
            return "delegate task must not be empty";
        }
        if (request.getModeSnapshot() == null) {
            return "delegate mode snapshot is missing";
        }
        if (request.getParentRunId() == null || request.getParentRunId().isBlank()
                || request.getRootRunId() == null || request.getRootRunId().isBlank()) {
            return "delegate lineage is incomplete";
        }
        if (request.getWorkspaceRoot() == null || request.getWorkspaceRoot().isBlank()) {
            return "delegate workspace boundary is missing";
        }
        if (request.getParentDepth() >= request.getMaxDepth()) {
            return "delegate depth limit reached";
        }
        if (request.getChildMaxSteps() <= 0 || request.getChildMaxAttempts() <= 0) {
            return "delegate remaining step limit reached";
        }
        if (request.getRemainingTimeoutMs() <= 0) {
            return "delegate remaining timeout reached";
        }
        try {
            Path.of(request.getWorkspaceRoot()).toRealPath();
        } catch (Exception e) {
            return "delegate workspace boundary is invalid";
        }
        return null;
    }

    private ToolRegistry readOnlyToolRegistry(DelegateRequest request) {
        ToolRegistry registry = toolRegistryProvider.getObject();
        List<String> allowed = request.getAllowedTools() == null ? List.of() : request.getAllowedTools();
        List<AgentTool> childTools = registry.tools().stream()
                .filter(tool -> READ_ONLY_TOOLS.contains(tool.spec().getName()))
                .filter(tool -> allowed.contains(tool.spec().getName()))
                .toList();
        return new ToolRegistry(childTools, new ToolSchemaValidator(mapper));
    }

    private DelegateResult failed(DelegateRequest request, String message) {
        return DelegateResult.builder()
                .safeOutcome(safeText(message, "delegate child failed"))
                .status(AgentRunStatus.FAILED)
                .provenance(provenance(request, null, null))
                .evidenceReceipts(List.of())
                .errorCode("delegate_failed")
                .build();
    }

    private DelegateProvenance provenance(DelegateRequest request, String childRunId, AgentRun child) {
        String workspaceRoot = request.getWorkspaceRoot();
        try {
            workspaceRoot = Path.of(workspaceRoot).toRealPath().toString();
        } catch (Exception ignored) {
            // Validation already rejects this for an attempted child run.
        }
        return DelegateProvenance.builder()
                .runId(child == null ? childRunId : child.getRunId())
                .parentRunId(child == null ? request.getParentRunId() : child.getParentRunId())
                .rootRunId(child == null ? request.getRootRunId() : child.getRootRunId())
                .sessionId(child == null ? request.getSessionId() : child.getSessionId())
                .workspaceRoot(workspaceRoot)
                .modeSnapshot(child == null ? request.getModeSnapshot() : child.getRunModeSnapshot())
                .depth(child == null ? request.getParentDepth() + 1 : child.getDepth())
                .build();
    }

    private String safeText(String value, String fallback) {
        String safe = value == null || value.isBlank() ? fallback : value;
        return safe.length() <= MAX_SAFE_OUTCOME_CHARS
                ? safe : safe.substring(0, MAX_SAFE_OUTCOME_CHARS);
    }
}
