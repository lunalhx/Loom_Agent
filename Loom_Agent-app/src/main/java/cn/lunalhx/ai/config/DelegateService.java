package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.adapter.port.DelegateRunner;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopFactory;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a bounded read-only delegate child agent for the {@code delegate} tool
 * as a real child run: it inherits parent/root/session/workspace lineage, has
 * its own independent ledger and working memory (it never mutates the parent
 * session), receives only the parent task plus a short summary, uses only
 * list/read/search tools, and is capped at three steps / one depth level.
 */
@Component
public class DelegateService implements DelegateRunner {

    private static final int MAX_PARENT_SUMMARY_CHARS = 300;

    private final AgentLoopFactory loopFactory;
    private final ObjectProvider<ToolRegistry> toolRegistryProvider;

    public DelegateService(AgentLoopFactory loopFactory, ObjectProvider<ToolRegistry> toolRegistryProvider) {
        this.loopFactory = loopFactory;
        this.toolRegistryProvider = toolRegistryProvider;
    }

    @Override
    public String delegate(String task, int maxSteps, String parentRunId, String rootRunId,
                           String sessionId, String workspace, String parentSummary,
                           CollaborationMode collaborationMode) {
        AgentQuestion question = AgentQuestion.builder()
                .question(task)
                .parentRunId(parentRunId)
                .rootRunId(rootRunId == null || rootRunId.isBlank() ? parentRunId : rootRunId)
                .sessionId(sessionId)
                .workspace(workspace)
                .agentDepth(1)
                .maxSteps(Math.min(3, maxSteps))
                .approvalPolicy("never")
                .collaborationMode(Objects.requireNonNull(collaborationMode,
                        "delegate collaboration mode must not be null"))
                .allowedTools(List.of("list_files", "read_file", "search"))
                .build();
        AtomicReference<String> answer = new AtomicReference<>("");
        AtomicReference<String> error = new AtomicReference<>("");

        Flux<AgentEvent> events;
        try {
            events = loopFactory.createStandalone(baseToolRegistry(), Runnable::run).ask(question);
        } catch (Exception e) {
            return "delegate_result:\n" + "error: " + e.getMessage();
        }
        events.doOnNext(event -> {
            if (event.getType() == AgentEventType.ANSWER && event.getAnswer() != null) {
                answer.set(event.getAnswer());
            }
            if (event.getType() == AgentEventType.ERROR) {
                error.set(String.valueOf(event.getMessage()));
            }
        }).blockLast();
        String result = answer.get();
        if (result == null || result.isBlank()) {
            result = error.get() == null || error.get().isBlank() ? "(empty)" : "error: " + error.get();
        }
        return "delegate_result:\n" + result;
    }

    private ToolRegistry baseToolRegistry() {
        ToolRegistry toolRegistry = toolRegistryProvider.getObject();
        return new ToolRegistry(toolRegistry.tools().stream()
                .filter(t -> !ToolRegistry.DELEGATE_TOOL_NAME.equals(t.spec().getName()))
                .toList(), new cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator(
                new com.fasterxml.jackson.databind.ObjectMapper()));
    }
}
