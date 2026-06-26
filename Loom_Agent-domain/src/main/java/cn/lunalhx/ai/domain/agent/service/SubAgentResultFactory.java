package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.entity.SubAgentResult;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentTask;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRole;
import cn.lunalhx.ai.domain.agent.model.valobj.SubAgentStatus;
import org.apache.commons.lang3.StringUtils;

class SubAgentResultFactory {

    SubAgentResult success(SubAgentTask task, String runId, AgentRole role, String answer,
                           boolean truncated, int stepCount, long elapsedMs) {
        return SubAgentResult.builder()
                .taskId(task.getTaskId())
                .runId(runId)
                .role(role)
                .status(SubAgentStatus.SUCCEEDED)
                .summary(answer)
                .truncated(truncated)
                .stepCount(stepCount)
                .elapsedMs(elapsedMs)
                .build();
    }

    SubAgentResult failed(SubAgentTask task, String runId, String code, String message, long elapsedMs) {
        return SubAgentResult.builder()
                .taskId(task.getTaskId())
                .runId(runId)
                .role(task.getRole())
                .status(SubAgentStatus.FAILED)
                .errorCode(StringUtils.defaultIfBlank(code, "sub_agent_failed"))
                .message(StringUtils.defaultIfBlank(message, "子 Agent 执行失败"))
                .elapsedMs(elapsedMs)
                .build();
    }

    SubAgentResult timeout(SubAgentTask task, String runId, long elapsedMs) {
        return SubAgentResult.builder()
                .taskId(task.getTaskId())
                .runId(runId)
                .role(task.getRole())
                .status(SubAgentStatus.TIMEOUT)
                .errorCode("sub_agent_timeout")
                .message("子 Agent 执行超时")
                .elapsedMs(elapsedMs)
                .build();
    }

    SubAgentResult interrupted(SubAgentTask task, long elapsedMs) {
        return SubAgentResult.builder()
                .taskId(task.getTaskId())
                .runId(null)
                .role(task.getRole())
                .status(SubAgentStatus.FAILED)
                .errorCode("sub_agent_interrupted")
                .message("子 Agent 被中断")
                .elapsedMs(elapsedMs)
                .build();
    }

    SubAgentResult noAnswer(SubAgentTask task, String runId, String errorCode, String message, long elapsedMs) {
        return SubAgentResult.builder()
                .taskId(task.getTaskId())
                .runId(runId)
                .role(task.getRole())
                .status(SubAgentStatus.FAILED)
                .errorCode(StringUtils.defaultIfBlank(errorCode, "sub_agent_no_answer"))
                .message(StringUtils.defaultIfBlank(message, "子 Agent 未生成 final answer"))
                .elapsedMs(elapsedMs)
                .build();
    }

    SubAgentResult partial(SubAgentTask task, String runId, AgentRole role, String summary,
                           boolean truncated, int stepCount, long elapsedMs) {
        return SubAgentResult.builder()
                .taskId(task.getTaskId())
                .runId(runId)
                .role(role)
                .status(SubAgentStatus.PARTIAL)
                .summary(summary)
                .truncated(truncated)
                .stepCount(stepCount)
                .elapsedMs(elapsedMs)
                .build();
    }
}
