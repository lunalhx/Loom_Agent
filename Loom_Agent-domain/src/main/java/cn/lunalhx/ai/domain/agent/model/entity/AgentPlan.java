package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentPlanItemStatus;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Data
public class AgentPlan {

    private String planId = UUID.randomUUID().toString();
    private int version = 1;
    private String currentItemId;
    private int roundsSinceUpdate;
    private String lastUpdateReason;
    private Instant updatedAt = Instant.now();
    private List<AgentPlanItem> items = new ArrayList<>();

    public static AgentPlan forQuestion(String question) {
        AgentPlan plan = new AgentPlan();
        String text = StringUtils.defaultString(question);
        if (text.contains("缓存") || StringUtils.containsIgnoreCase(text, "cache")) {
            plan.addItem("理解目标模块现有实现和调用路径", AgentPlanItemStatus.IN_PROGRESS);
            plan.addItem("设计并实现最小范围缓存逻辑", AgentPlanItemStatus.PENDING);
            plan.addItem("补充或调整相关单元测试", AgentPlanItemStatus.PENDING);
            plan.addItem("运行相关 Maven 测试并修复失败", AgentPlanItemStatus.PENDING);
            plan.addItem("总结改动、测试结果和剩余风险", AgentPlanItemStatus.PENDING);
        } else {
            plan.addItem("理解用户任务和相关代码上下文", AgentPlanItemStatus.IN_PROGRESS);
            plan.addItem("执行必要的代码检索、修改或验证", AgentPlanItemStatus.PENDING);
            plan.addItem("给出包含证据和结果的最终答复", AgentPlanItemStatus.PENDING);
        }
        plan.refreshCurrentItem();
        plan.setLastUpdateReason("initial_plan");
        return plan;
    }

    public void applyTodoWrite(JsonNode input) {
        JsonNode todos = input == null ? null : input.path("todos");
        if (todos == null || !todos.isArray()) {
            throw new IllegalArgumentException("todos 必须是数组");
        }
        int order = 1;
        for (JsonNode todo : todos) {
            String content = todo.path("content").asText(null);
            if (StringUtils.isBlank(content)) {
                throw new IllegalArgumentException("todo.content 不能为空");
            }
            AgentPlanItemStatus status = AgentPlanItemStatus.from(todo.path("status").asText("pending"));
            String id = todo.path("id").asText(null);
            AgentPlanItem item = findForUpdate(id, content).orElseGet(() -> {
                AgentPlanItem created = AgentPlanItem.builder()
                        .id(StringUtils.defaultIfBlank(id, "task-" + (items.size() + 1)))
                        .content(content)
                        .build();
                items.add(created);
                return created;
            });
            item.setOrder(order++);
            item.setContent(content);
            item.setStatus(status);
            item.setEvidence(todo.path("evidence").asText(item.getEvidence()));
            item.setBlocker(todo.path("blocker").asText(item.getBlocker()));
            item.setUpdateTime(Instant.now());
        }
        touch("todo_write");
    }

    public void addReplanItem(String content, String reason) {
        if (items.stream().noneMatch(item -> StringUtils.equals(item.getContent(), content))) {
            addItem(content, AgentPlanItemStatus.PENDING);
        }
        touch(reason);
    }

    public boolean hasIncompleteItems() {
        return items.stream().anyMatch(AgentPlanItem::incomplete);
    }

    public void incrementRoundsSinceUpdate() {
        roundsSinceUpdate++;
    }

    public String render() {
        return items.stream()
                .sorted(Comparator.comparing(AgentPlanItem::getOrder, Comparator.nullsLast(Integer::compareTo)))
                .map(item -> "- [" + (item.getStatus() == null ? AgentPlanItemStatus.PENDING.code() : item.getStatus().code())
                        + "] " + item.getId() + ": " + item.getContent()
                        + appendIfPresent(" evidence=", item.getEvidence())
                        + appendIfPresent(" blocker=", item.getBlocker()))
                .collect(Collectors.joining("\n"));
    }

    public Map<String, Object> toView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("planId", planId);
        view.put("version", version);
        view.put("currentItemId", currentItemId);
        view.put("roundsSinceUpdate", roundsSinceUpdate);
        view.put("lastUpdateReason", lastUpdateReason);
        view.put("items", items.stream()
                .sorted(Comparator.comparing(AgentPlanItem::getOrder, Comparator.nullsLast(Integer::compareTo)))
                .map(AgentPlanItem::toView)
                .collect(Collectors.toList()));
        return view;
    }

    private void addItem(String content, AgentPlanItemStatus status) {
        items.add(AgentPlanItem.builder()
                .id("task-" + (items.size() + 1))
                .order(items.size() + 1)
                .content(content)
                .status(status)
                .updateTime(Instant.now())
                .build());
    }

    private Optional<AgentPlanItem> findForUpdate(String id, String content) {
        if (StringUtils.isNotBlank(id)) {
            Optional<AgentPlanItem> byId = items.stream()
                    .filter(item -> StringUtils.equals(item.getId(), id))
                    .findFirst();
            if (byId.isPresent()) {
                return byId;
            }
        }
        return items.stream()
                .filter(item -> StringUtils.equals(item.getContent(), content))
                .findFirst();
    }

    private void touch(String reason) {
        version++;
        roundsSinceUpdate = 0;
        updatedAt = Instant.now();
        lastUpdateReason = reason;
        refreshCurrentItem();
    }

    private void refreshCurrentItem() {
        currentItemId = items.stream()
                .filter(item -> item.getStatus() == AgentPlanItemStatus.IN_PROGRESS)
                .findFirst()
                .or(() -> items.stream().filter(AgentPlanItem::incomplete).findFirst())
                .map(AgentPlanItem::getId)
                .orElse(null);
    }

    private String appendIfPresent(String prefix, String value) {
        return StringUtils.isBlank(value) ? "" : prefix + value;
    }

}
