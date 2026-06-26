package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SubAgentPartialSummaryGenerator {

    private final ObjectMapper objectMapper;

    SubAgentPartialSummaryGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String generate(AgentContext context) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", buildSummary(context));
        result.put("findings", buildFindings(context));
        result.put("confidence", estimateConfidence(context));
        result.put("truncated", true);
        result.put("followUp", buildFollowUp(context));
        result.put("partial", true);
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"summary\":\"子 Agent 部分结果生成失败\",\"partial\":true}";
        }
    }

    private String buildSummary(AgentContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("[部分结果] ");
        if (context.getPlan() != null && !context.getPlan().getItems().isEmpty()) {
            long completed = context.getPlan().getItems().stream()
                    .filter(item -> item.getStatus() != null && item.getStatus().terminal())
                    .count();
            sb.append("计划共 ").append(context.getPlan().getItems().size())
                    .append(" 项，已完成 ").append(completed).append(" 项。");
        }
        if (context.getDecision() != null && context.getDecision().getThought() != null) {
            sb.append(" 最近思考：").append(abbreviate(context.getDecision().getThought(), 500));
        }
        if (sb.isEmpty()) {
            sb.append("子 Agent 因超时被中断，未产生完整结果。");
        }
        return sb.toString();
    }

    private List<Map<String, Object>> buildFindings(AgentContext context) {
        List<Map<String, Object>> findings = new ArrayList<>();
        if (context.getPlan() != null) {
            context.getPlan().getItems().stream()
                    .filter(item -> item.getStatus() != null && item.getStatus().terminal())
                    .forEach(item -> {
                        Map<String, Object> finding = new LinkedHashMap<>();
                        finding.put("description", item.getContent());
                        finding.put("status", item.getStatus().name());
                        findings.add(finding);
                    });
        }
        if (findings.isEmpty()) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("description", "子 Agent 在产生完整发现前被中断");
            fallback.put("status", "PARTIAL");
            findings.add(fallback);
        }
        return findings;
    }

    private double estimateConfidence(AgentContext context) {
        if (context.getPlan() == null || context.getPlan().getItems().isEmpty()) {
            return 0.1;
        }
        long total = context.getPlan().getItems().size();
        long completed = context.getPlan().getItems().stream()
                .filter(item -> item.getStatus() != null && item.getStatus().terminal())
                .count();
        return Math.min(0.5, (double) completed / Math.max(total, 1));
    }

    private String buildFollowUp(AgentContext context) {
        if (context.getPlan() != null) {
            List<String> pending = context.getPlan().getItems().stream()
                    .filter(item -> item.getStatus() == null || !item.getStatus().terminal())
                    .map(item -> item.getContent())
                    .collect(Collectors.toList());
            if (!pending.isEmpty()) {
                return "未完成项：" + String.join("; ", pending.subList(0, Math.min(5, pending.size())));
            }
        }
        return "建议父 Agent 根据以上部分结果决定是否需要重新派生子 Agent。";
    }

    private String abbreviate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
