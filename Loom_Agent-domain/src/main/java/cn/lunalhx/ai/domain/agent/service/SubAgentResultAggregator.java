package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentDispatchResult;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.SubAgentStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class SubAgentResultAggregator {

    private final AgentRuntimeProperties properties;
    private final ObjectMapper objectMapper;

    SubAgentResultAggregator(AgentRuntimeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    SubAgentDispatchResult aggregate(AgentContext parent, String reason,
                                      List<SubAgentResult> results, long startedAt) {
        String observation = toObservation(parent, reason, results);
        boolean truncated = false;
        int maxChars = positive(properties.getSubAgentSummaryMaxChars(), 12000);
        if (StringUtils.length(observation) > maxChars) {
            observation = StringUtils.abbreviate(observation, maxChars);
            truncated = true;
        }
        boolean anySucceeded = results.stream().anyMatch(result -> result.getStatus() == SubAgentStatus.SUCCEEDED);
        return SubAgentDispatchResult.builder()
                .success(anySucceeded)
                .errorCode(anySucceeded ? null : "sub_agent_all_failed")
                .message(anySucceeded ? null : "所有子 Agent 均未成功完成")
                .observation(observation)
                .truncated(truncated)
                .elapsedMs(elapsed(startedAt))
                .results(results)
                .build();
    }

    private String toObservation(AgentContext parent, String reason, List<SubAgentResult> results) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "sub_agent_summary");
        root.put("parentRunId", parent.getRunId());
        root.put("reason", reason);
        root.put("total", results.size());
        root.put("succeeded", results.stream().filter(result -> result.getStatus() == SubAgentStatus.SUCCEEDED).count());
        root.put("failed", results.stream().filter(result -> result.getStatus() != SubAgentStatus.SUCCEEDED).count());
        List<Map<String, Object>> resultViews = new ArrayList<>();
        for (SubAgentResult result : results) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("taskId", result.getTaskId());
            view.put("runId", result.getRunId());
            view.put("role", result.getRole() == null ? null : result.getRole().name());
            view.put("status", result.getStatus() == null ? null : result.getStatus().name());
            view.put("summary", result.getSummary());
            view.put("errorCode", result.getErrorCode());
            view.put("message", result.getMessage());
            view.put("truncated", result.isTruncated());
            view.put("stepCount", result.getStepCount());
            view.put("elapsedMs", result.getElapsedMs());
            resultViews.add(view);
        }
        root.put("results", resultViews);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return String.valueOf(root);
        }
    }

    private long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }

    private int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
