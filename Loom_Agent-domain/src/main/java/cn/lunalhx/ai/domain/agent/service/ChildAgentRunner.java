package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentResult;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentTask;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRole;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

class ChildAgentRunner implements SubAgentExecutionScheduler.TaskRunner {

    private final RoleToolRegistryFactory toolRegistryFactory;
    private final ChildAgentServiceFactory serviceFactory;
    private final AgentRuntimeProperties properties;
    private final SubAgentResultFactory resultFactory;

    ChildAgentRunner(RoleToolRegistryFactory toolRegistryFactory,
                     ChildAgentServiceFactory serviceFactory,
                     AgentRuntimeProperties properties,
                     SubAgentResultFactory resultFactory) {
        this.toolRegistryFactory = toolRegistryFactory;
        this.serviceFactory = serviceFactory;
        this.properties = properties;
        this.resultFactory = resultFactory;
    }

    @Override
    public SubAgentResult run(AgentContext parent, SubAgentTask task, int ordinal, long startedAt) {
        String childRunId = UUID.randomUUID().toString();
        try {
            AgentRole role = task.getRole() == null ? AgentRole.EXPLORER : task.getRole();
            ToolRegistry childRegistry = toolRegistryFactory.create(role);
            DefaultAgentLoopService childService = serviceFactory.create(childRegistry);
            List<AgentEvent> events = childService.ask(childQuestion(parent, task, ordinal, childRunId, role))
                    .onErrorResume(error -> Flux.just(AgentEvent.builder()
                            .type(AgentEventType.ERROR)
                            .runId(childRunId)
                            .code("sub_agent_error")
                            .message(error.getMessage())
                            .build()))
                    .collectList()
                    .block(Duration.ofMillis(positive(properties.getSubAgentTimeoutMs(), 60000L) + 1000L));
            if (events == null) {
                return resultFactory.timeout(task, childRunId, elapsed(startedAt));
            }
            AgentEvent answer = events.stream()
                    .filter(event -> event.getType() == AgentEventType.ANSWER)
                    .findFirst()
                    .orElse(null);
            if (answer != null && StringUtils.isNotBlank(answer.getAnswer())) {
                String clamped = clamp(answer.getAnswer());
                return resultFactory.success(task, childRunId, role, clamped,
                        StringUtils.length(answer.getAnswer()) > positive(properties.getSubAgentSummaryMaxChars(), 12000),
                        stepCount(events), elapsed(startedAt));
            }
            AgentEvent error = events.stream()
                    .filter(event -> event.getType() == AgentEventType.ERROR)
                    .findFirst()
                    .orElse(null);
            return resultFactory.noAnswer(task, childRunId,
                    error == null ? "sub_agent_no_answer" : error.getCode(),
                    error == null ? "子 Agent 未生成 final answer" : error.getMessage(),
                    elapsed(startedAt));
        } catch (Exception e) {
            return resultFactory.failed(task, childRunId, "sub_agent_failed", e.getMessage(), elapsed(startedAt));
        }
    }

    private AgentQuestion childQuestion(AgentContext parent,
                                        SubAgentTask task,
                                        int ordinal,
                                        String childRunId,
                                        AgentRole role) {
        String rootRunId = StringUtils.defaultIfBlank(parent.getRootRunId(), parent.getRunId());
        return AgentQuestion.builder()
                .runId(childRunId)
                .parentRunId(parent.getRunId())
                .rootRunId(rootRunId)
                .requestId(UUID.randomUUID().toString())
                .conversationId(parent.getConversationId())
                .traceId(parent.getTraceId())
                .agentRole(role)
                .agentDepth(parent.getAgentDepth() + 1)
                .childOrdinal(ordinal)
                .question(renderChildTask(parent, task, role))
                .pathScope(task.getPathScope())
                .workspace(parent.getResolvedWorkspace() == null ? null : parent.getResolvedWorkspace().toString())
                .maxSteps(task.getMaxSteps() == null ? parent.getMaxSteps() : task.getMaxSteps())
                .includeTrace(false)
                .subAgentSpawnAllowed(false)
                .build();
    }

    private String renderChildTask(AgentContext parent, SubAgentTask task, AgentRole role) {
        StringBuilder text = new StringBuilder();
        text.append("父 Agent 任务：").append(parent.getQuestion()).append('\n');
        text.append("你的角色：").append(role.name()).append('\n');
        text.append("你的子任务：").append(task.getQuestion()).append('\n');
        if (StringUtils.isNotBlank(task.getPathScope())) {
            text.append("路径范围：只在 ").append(task.getPathScope()).append(" 下探索；搜索工具请显式传入该 pathScope。\n");
        }
        if (StringUtils.isNotBlank(task.getExpectedOutput())) {
            text.append("期望输出：").append(task.getExpectedOutput()).append('\n');
        }
        text.append("最终只能输出 final JSON，answer 字段里放一个 JSON 字符串，包含 summary、findings、confidence、truncated、followUp。");
        return text.toString();
    }

    private String clamp(String text) {
        int maxChars = positive(properties.getSubAgentSummaryMaxChars(), 12000);
        return StringUtils.length(text) > maxChars ? StringUtils.abbreviate(text, maxChars) : text;
    }

    private int stepCount(List<AgentEvent> events) {
        return events.stream()
                .filter(event -> event.getType() == AgentEventType.DONE)
                .findFirst()
                .map(AgentEvent::getStepCount)
                .orElse(0);
    }

    private long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }

    private int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private long positive(Long value, long fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
