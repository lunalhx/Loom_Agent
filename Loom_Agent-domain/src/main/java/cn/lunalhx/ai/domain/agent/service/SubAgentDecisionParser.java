package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentTask;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRole;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

class SubAgentDecisionParser {

    SubAgentDispatchRequest parse(AgentDecision decision) {
        JsonNode input = decision == null ? null : decision.getInput();
        if (input == null || input.isMissingNode() || !input.path("tasks").isArray()) {
            return new SubAgentDispatchRequest(null, 0, List.of(),
                    "sub_agent_tasks_required", "spawn_agents.input.tasks 必须是数组");
        }
        String reason = text(input, "reason", "未说明");
        int maxConcurrency = Math.max(1, input.path("maxConcurrency").asInt(2));
        JsonNode tasksNode = input.path("tasks");
        List<SubAgentTask> tasks = new ArrayList<>();
        for (int i = 0; i < tasksNode.size(); i++) {
            JsonNode taskNode = tasksNode.get(i);
            String question = text(taskNode, "question", "");
            if (StringUtils.isBlank(question)) {
                return new SubAgentDispatchRequest(null, 0, List.of(),
                        "sub_agent_task_question_required", "子任务 question 不能为空");
            }
            tasks.add(SubAgentTask.builder()
                    .taskId(StringUtils.defaultIfBlank(text(taskNode, "taskId", null), "task-" + (i + 1)))
                    .role(parseRole(text(taskNode, "role", "explorer")))
                    .question(question)
                    .pathScope(text(taskNode, "pathScope", null))
                    .expectedOutput(text(taskNode, "expectedOutput", null))
                    .maxSteps(taskNode.path("maxSteps").isNumber() ? Math.max(1, taskNode.path("maxSteps").asInt()) : null)
                    .build());
        }
        if (tasks.isEmpty()) {
            return new SubAgentDispatchRequest(null, 0, List.of(),
                    "sub_agent_tasks_required", "至少需要一个子任务");
        }
        return new SubAgentDispatchRequest(reason, maxConcurrency, tasks, null, null);
    }

    AgentRole parseRole(String value) {
        try {
            return AgentRole.valueOf(StringUtils.upperCase(StringUtils.defaultIfBlank(value, "explorer")));
        } catch (Exception e) {
            return AgentRole.EXPLORER;
        }
    }

    private String text(JsonNode node, String field, String defaultValue) {
        if (node == null || node.path(field).isMissingNode() || node.path(field).isNull()) {
            return defaultValue;
        }
        return node.path(field).asText(defaultValue);
    }
}
