package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.memory.service.MemorySelectionService;
import cn.lunalhx.ai.domain.memory.service.WorkspaceKeyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Memory recall node — runs once per new root-agent conversation.
 * <p>Retrieves pinned and relevant long-term memories, saves the rendered
 * text in context state, and the {@code ConversationLedgerInitializer} injects
 * it into the ledger as a {@code LONG_TERM_MEMORY} entry after {@code USER_TASK}.
 * <p>Fail-open: exceptions are logged and the agent continues without memories.
 * Child agents, continuations, and checkpoint resumes skip this node.
 */
public class MemoryRecallNode extends AbstractAgentNode {

    private static final Logger log = LoggerFactory.getLogger(MemoryRecallNode.class);

    private final MemorySelectionService memorySelectionService;

    public MemoryRecallNode(MemorySelectionService memorySelectionService) {
        super(AgentNodeNames.MEMORY_RECALL, List.of("memoryRecall"));
        this.memorySelectionService = memorySelectionService;
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        if (context.getParentRunId() != null) {
            return NodeResult.next(AgentNodeNames.START, List.of());
        }

        if (context.isMemoryRecallExecuted()) {
            return NodeResult.next(AgentNodeNames.START, List.of());
        }

        try {
            String workspacePath = context.getWorkspace() != null ? context.getWorkspace().getLocation() : null;
            if (workspacePath == null) {
                context.setMemoryRecallExecuted(true);
                return NodeResult.next(AgentNodeNames.START, List.of());
            }

            String workspaceKey = WorkspaceKeyUtil.compute(workspacePath);
            String question = context.getQuestion();

            long startedAt = System.currentTimeMillis();
            MemorySelectionService.SelectionResult result = memorySelectionService.select(workspaceKey, question);
            long elapsedMs = System.currentTimeMillis() - startedAt;

            List<AgentEvent> events = new ArrayList<>();

            if (!result.isEmpty()) {
                String wrappedText = memorySelectionService.renderWrappedText(result);
                context.setMemoryRecallRenderedText(wrappedText);
                context.setMemoryRecallCount(result.memories().size());
                context.setMemoryRecallChars(result.totalChars());
                context.setMemoryRecallIds(new ArrayList<>(result.selectedIds()));

                events.add(buildMemoryRecalledEvent(context, result, elapsedMs));
            }

            context.setMemoryRecallExecuted(true);
            return NodeResult.next(AgentNodeNames.START, events);

        } catch (Exception e) {
            log.warn("Memory recall failed, continuing without memories: {}", e.getMessage());
            context.setMemoryRecallExecuted(true);
            return NodeResult.next(AgentNodeNames.START, List.of());
        }
    }

    private AgentEvent buildMemoryRecalledEvent(AgentContext context,
                                                 MemorySelectionService.SelectionResult result,
                                                 long elapsedMs) {
        return event(context, AgentEventType.MEMORY_RECALLED)
                .step(context.runtime().step())
                .metadata(Map.of(
                        "count", String.valueOf(context.getMemoryRecallCount()),
                        "chars", String.valueOf(context.getMemoryRecallChars()),
                        "ids", String.join(",", context.getMemoryRecallIds()),
                        "elapsedMs", String.valueOf(elapsedMs)))
                .build();
    }
}
