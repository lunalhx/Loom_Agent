package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentPlanItemStatus;
import cn.lunalhx.ai.domain.tool.model.ToolOperation;
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
import java.util.Set;
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
            plan.addItem("理解目标模块现有实现和调用路径", AgentPlanItemStatus.IN_PROGRESS, "inspect");
            plan.addItem("设计并实现最小范围缓存逻辑", AgentPlanItemStatus.PENDING, null);
            plan.addItem("补充或调整相关单元测试", AgentPlanItemStatus.PENDING, null);
            plan.addItem("运行相关 Maven 测试并修复失败", AgentPlanItemStatus.PENDING, null);
            plan.addItem("总结改动、测试结果和剩余风险", AgentPlanItemStatus.PENDING, null);
        } else {
            plan.addItem("理解用户任务和相关代码上下文", AgentPlanItemStatus.IN_PROGRESS, "inspect");
            plan.addItem("执行必要的代码检索、修改或验证", AgentPlanItemStatus.PENDING, null);
            plan.addItem("给出包含证据和结果的最终答复", AgentPlanItemStatus.PENDING, null);
        }
        plan.refreshCurrentItem();
        plan.setLastUpdateReason("initial_plan");
        return plan;
    }

    /**
     * Apply a todo_write upsert to the plan.
     *
     * <h3>Create vs Update</h3>
     * <ul>
     *   <li><b>Create</b> — item has no {@code id} that matches an existing item AND
     *       has non-blank {@code content}. Created items require {@code kind} and,
     *       for kind=edit, non-empty {@code targets}.</li>
     *   <li><b>Update</b> — item has an {@code id} that matches an existing item.
     *       Only the fields that are <em>explicitly provided</em> in the JSON are
     *       updated; omitted fields keep their current values. Status is never
     *       reset to pending on update unless explicitly requested.</li>
     * </ul>
     *
     * <p>Per PLAN.md §3: "新建项：要求 content/status/kind；kind=edit 时必须有非空 targets.
     * 更新项：已有 id 即可按字段 patch，允许只更新 status/evidence."</p>
     */
    public void applyTodoWrite(JsonNode input) {
        JsonNode todos = input == null ? null : input.path("todos");
        if (todos == null || !todos.isArray()) {
            throw new IllegalArgumentException("todos 必须是数组");
        }
        for (JsonNode todo : todos) {
            String content = todo.path("content").asText(null);
            String id = todo.path("id").asText(null);
            AgentPlanItem existing = findForUpdate(id, content).orElse(null);

            if (existing != null) {
                if (todo.has("content") && StringUtils.isNotBlank(content)) {
                    existing.setContent(content);
                }
                if (todo.has("status")) {
                    existing.setStatus(AgentPlanItemStatus.from(todo.path("status").asText()));
                }
                if (todo.has("evidence")) {
                    existing.setEvidence(todo.path("evidence").asText(existing.getEvidence()));
                }
                if (todo.has("blocker")) {
                    existing.setBlocker(todo.path("blocker").asText(existing.getBlocker()));
                }
                if (todo.has("kind")) {
                    String kind = todo.path("kind").asText(null);
                    validateKind(kind);
                    existing.setKind(kind);
                }
                if (todo.path("targets").isArray()) {
                    existing.setTargets(readTargets(todo.path("targets")));
                }
                if ("edit".equalsIgnoreCase(existing.getKind())
                        && (existing.getTargets() == null || existing.getTargets().isEmpty())) {
                    if (existing.getStatus() == null || !existing.getStatus().terminal()) {
                        throw new IllegalArgumentException("kind=edit 的任务必须提供非空 targets");
                    }
                    if (existing.getTargets() == null) {
                        existing.setTargets(List.of());
                    }
                }
                existing.setUpdateTime(Instant.now());

            } else if (StringUtils.isNotBlank(content)) {
                if (StringUtils.isBlank(id)) {
                    id = "task-" + UUID.randomUUID().toString().substring(0, 8);
                }
                String kind = todo.path("kind").asText(null);
                validateKind(kind);
                if (!todo.has("status")) {
                    throw new IllegalArgumentException("新建任务必须提供 status");
                }
                AgentPlanItemStatus status =
                        AgentPlanItemStatus.from(todo.path("status").asText());
                List<String> targets = readTargets(todo.path("targets"));
                if ("edit".equalsIgnoreCase(kind) && targets.isEmpty()) {
                    throw new IllegalArgumentException("kind=edit 的任务必须提供非空 targets");
                }
                AgentPlanItem created = AgentPlanItem.builder()
                        .id(id)
                        .content(content)
                        .status(status)
                        .kind(kind)
                        .targets(targets)
                        .evidence(todo.path("evidence").asText(null))
                        .blocker(todo.path("blocker").asText(null))
                        .order(nextOrder())
                        .updateTime(Instant.now())
                        .build();
                items.add(created);

            } else {
                throw new IllegalArgumentException(
                        "无法找到 id=\"" + StringUtils.defaultString(id) + "\" 的任务项进行更新，"
                                + "且未提供 content 用于创建新任务。"
                                + "请提供一个已知 id 以更新现有任务，或提供 content 创建新任务。");
            }
        }
        touch("todo_write");
    }

    public void addReplanItem(String content, String reason) {
        addReplanItem(content, reason, null);
    }

    public void addReplanItem(String content, String reason, String kind) {
        if (items.stream().noneMatch(item -> StringUtils.equals(item.getContent(), content))) {
            addItem(content, AgentPlanItemStatus.PENDING, kind);
        }
        touch(reason);
    }

    public boolean hasIncompleteItems() {
        return items.stream().anyMatch(AgentPlanItem::incomplete);
    }

    public boolean hasDeclaredEditTarget(String path) {
        if (StringUtils.isBlank(path)) {
            return false;
        }
        String normalized = ToolOperation.normalizePath(path);
        return items.stream()
                .filter(item -> "edit".equalsIgnoreCase(item.getKind()))
                .filter(item -> item.getTargets() != null)
                .flatMap(item -> item.getTargets().stream())
                .map(ToolOperation::normalizePath)
                .anyMatch(normalized::equals);
    }

    public boolean hasVerifyItem() {
        return items.stream().anyMatch(item ->
                "verify".equalsIgnoreCase(item.getKind()));
    }

    public long unmetEditTargetCount(Set<String> touchedFiles) {
        Set<String> touched = touchedFiles == null
                ? Set.of()
                : touchedFiles.stream()
                        .map(ToolOperation::normalizePath)
                        .collect(Collectors.toSet());
        return items.stream()
                .filter(item -> "edit".equalsIgnoreCase(item.getKind()))
                .filter(item -> item.getTargets() != null)
                .flatMap(item -> item.getTargets().stream())
                .map(ToolOperation::normalizePath)
                .filter(target -> !touched.contains(target))
                .distinct()
                .count();
    }

    /**
     * Count incomplete edit items regardless of whether targets are declared.
     * Used by the stop hook to detect edit items that need targets before
     * they can be auto-completed.
     */
    public long incompleteEditItemCount() {
        return items.stream()
                .filter(item -> "edit".equalsIgnoreCase(item.getKind()))
                .filter(AgentPlanItem::incomplete)
                .count();
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

    /**
     * Full render including kind and targets for replan prompts.
     */
    public String renderFull() {
        return items.stream()
                .sorted(Comparator.comparing(AgentPlanItem::getOrder, Comparator.nullsLast(Integer::compareTo)))
                .map(item -> "- [" + (item.getStatus() == null ? AgentPlanItemStatus.PENDING.code() : item.getStatus().code())
                        + "] " + item.getId() + ": " + item.getContent()
                        + appendIfPresent(" kind=", item.getKind())
                        + (item.getTargets() != null && !item.getTargets().isEmpty()
                                ? " targets=" + item.getTargets() : "")
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
        addItem(content, status, null);
    }

    private void addItem(String content, AgentPlanItemStatus status, String kind) {
        AgentPlanItem.AgentPlanItemBuilder builder = AgentPlanItem.builder()
                .id("task-" + (items.size() + 1))
                .order(items.size() + 1)
                .content(content)
                .status(status)
                .updateTime(Instant.now());
        if (kind != null) {
            builder.kind(kind);
            if ("verify".equals(kind)) {
                builder.targets(List.of());
            }
        }
        items.add(builder.build());
    }

    private Optional<AgentPlanItem> findForUpdate(String id, String content) {
        if (StringUtils.isNotBlank(id)) {
            return items.stream()
                    .filter(item -> StringUtils.equals(item.getId(), id))
                    .findFirst();
        }
        return items.stream()
                .filter(item -> StringUtils.equals(item.getContent(), content))
                .findFirst();
    }

    private List<String> readTargets(JsonNode targetsNode) {
        if (targetsNode == null || !targetsNode.isArray()) {
            return List.of();
        }
        List<String> targets = new ArrayList<>();
        targetsNode.forEach(target -> {
            if (StringUtils.isNotBlank(target.asText())) {
                targets.add(ToolOperation.normalizePath(target.asText()));
            }
        });
        return List.copyOf(targets);
    }

    private void validateKind(String kind) {
        if (StringUtils.isBlank(kind)
                || !List.of("inspect", "edit", "verify").contains(kind)) {
            throw new IllegalArgumentException("kind 只能是 inspect、edit 或 verify");
        }
    }

    private int nextOrder() {
        return items.stream()
                .map(AgentPlanItem::getOrder)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
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
