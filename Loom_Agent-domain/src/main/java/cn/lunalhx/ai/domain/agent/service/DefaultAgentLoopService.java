package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.flow.AgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.UserInputAction;
import cn.lunalhx.ai.domain.agent.model.valobj.WorkspaceResolutionException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

@Slf4j
public class DefaultAgentLoopService implements AgentLoopService {

    private final AgentRuntimeProperties properties;
    private final Map<String, AgentNode> nodes;
    private final AgentLoopComponents components;
    private final Executor executor;
    private final UndoSessionCoordinator undoCoordinator;

    // ==================== 生产构造器 ====================

    DefaultAgentLoopService(AgentLoopAssembly assembly, Executor executor) {
        this.properties = assembly.properties();
        this.nodes = assembly.flow().nodes();
        this.components = assembly.components();
        this.executor = executor;
        this.undoCoordinator = assembly.undoCoordinator();
    }

    // ==================== 公共入口 ====================

    @Override
    public Flux<AgentEvent> ask(AgentQuestion question) {
        return executeAsync("ask", question == null ? null : question.getWorkspace(), sink -> {
            AgentContext context = resolveContext(question);
            if (undoCoordinator != null) {
                undoCoordinator.onRunStart(context);
            }
            components.nodeLifecycle().userPromptSubmitted(context, events -> emit(sink, events));
            runLoop(context, AgentNodeNames.START, sink);
        });
    }

    private AgentContext resolveContext(AgentQuestion question) {
        String conversationId = question.getConversationId();
        if (StringUtils.isBlank(conversationId)) {
            return components.contextFactory().create(question);
        }

        AgentRun previousRun = components.runRepository().findLatestRootByConversationId(conversationId).orElse(null);
        if (previousRun == null) {
            return components.contextFactory().create(question);
        }

        AgentCheckpoint checkpoint = components.checkpointRepository().latest(previousRun.getRunId()).orElse(null);
        AgentContextSnapshot previous = checkpoint != null ? checkpoint.getContextSnapshot() : null;
        return components.contextFactory().createContinuation(question, previous);
    }

    @Override
    public Flux<AgentEvent> resume(String approvalId, ApprovalDecision decision, String reason) {
        return executeAsync("resume", approvalId, sink -> {
            AgentResumePlan plan = components.resumeCoordinator().prepareApprovalResume(approvalId, decision, reason);
            continueFrom(plan, sink);
        });
    }

    @Override
    public Flux<AgentEvent> resumeRun(String runId) {
        return executeAsync("resumeRun", runId, sink -> {
            AgentResumePlan plan = components.resumeCoordinator().prepareRunResume(runId);
            continueFrom(plan, sink);
        });
    }

    @Override
    public Flux<AgentEvent> resumeWithUserInput(String runId, UserInputAction action, String message) {
        return executeAsync("resumeWithUserInput", runId, sink -> {
            AgentResumePlan plan = components.resumeCoordinator().prepareUserInputResume(runId, action, message);
            continueFrom(plan, sink);
        });
    }

    // ==================== 核心编排 ====================

    private Flux<AgentEvent> executeAsync(String operation, String reference,
                                          Consumer<FluxSink<AgentEvent>> action) {
        return Flux.create(sink -> executor.execute(() -> {
            try {
                action.accept(sink);
            } catch (WorkspaceResolutionException e) {
                emit(sink, List.of(components.eventFactory().workspaceError(e)));
                sink.complete();
            } catch (Exception e) {
                log.error("Agent loop failed before terminal event, operation={}, reference={}",
                        operation,
                        reference == null ? null : StringUtils.abbreviate(reference, 200),
                        e);
                emit(sink, List.of(components.eventFactory().agentError()));
                sink.complete();
            } finally {
                MDC.clear();
            }
        }), FluxSink.OverflowStrategy.BUFFER);
    }

    private void continueFrom(AgentResumePlan plan, FluxSink<AgentEvent> sink) {
        emit(sink, plan.initialEvents());
        if (plan.terminal()) {
            sink.complete();
            return;
        }
        runLoop(plan.context(), plan.startNode(), sink);
    }

    private void runLoop(AgentContext context, String currentNode, FluxSink<AgentEvent> sink) {
        while (!sink.isCancelled()) {
            if (isTotalTimeout(context)) {
                fail(context, AgentStopReason.TIMEOUT, "agent_timeout", "Agent 执行超时");
                currentNode = AgentNodeNames.FAIL;
            }

            AgentNode node = nodes.get(currentNode);
            context.setCurrentNode(currentNode);
            if (node == null) {
                fail(context, AgentStopReason.MODEL_ERROR, "node_not_found", "未知节点：" + currentNode);
                node = nodes.get(AgentNodeNames.FAIL);
            }

            AgentNodeExecution execution =
                    components.nodeLifecycle().execute(context, node, events -> emit(sink, events));

            if (execution.terminal() && execution.hasDeferredTerminalEvents()) {
                execution = components.nodeLifecycle().resolveStop(
                        context, node, execution.terminalEvents(), events -> emit(sink, events));
            }

            if (execution.isStopContinued()) {
                currentNode = execution.nextNode();
                continue;
            }

            if (execution.terminal()) {
                sink.complete();
                return;
            }

            currentNode = execution.nextNode();
        }
    }

    // ==================== 私有辅助 ====================

    private void fail(AgentContext context, AgentStopReason reason, String code, String message) {
        context.setStopReason(reason);
        context.setErrorCode(code);
        context.setErrorMessage(message);
    }

    private boolean isTotalTimeout(AgentContext context) {
        return Duration.between(context.getStartedAt(), Instant.now()).toMillis() > properties.getTotalTimeoutMs();
    }

    private void emit(FluxSink<AgentEvent> sink, List<AgentEvent> events) {
        for (AgentEvent event : events) {
            if (!sink.isCancelled()) {
                sink.next(event);
            }
        }
    }
}
