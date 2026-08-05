package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopFactory;
import cn.lunalhx.ai.domain.agent.adapter.port.DelegateRunner;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a bounded read-only delegate child agent for the {@code delegate} tool.
 * The child uses the six base tools (no delegate), {@code approvalPolicy=never},
 * and returns only the final {@code delegate_result}.
 */
@Component
public class DelegateService implements DelegateRunner {

    private static final int MAX_PARENT_HISTORY_CHARS = 300;

    private final AgentLoopFactory loopFactory;
    private final ObjectProvider<ToolRegistry> toolRegistryProvider;

    public DelegateService(AgentLoopFactory loopFactory, ObjectProvider<ToolRegistry> toolRegistryProvider) {
        this.loopFactory = loopFactory;
        this.toolRegistryProvider = toolRegistryProvider;
    }

    @Override
    public String delegate(String task, int maxSteps) {
        AgentQuestion question = AgentQuestion.builder()
                .question(task)
                .parentRunId("delegate")
                .rootRunId("delegate")
                .agentDepth(1)
                .maxSteps(maxSteps)
                .approvalPolicy("never")
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
