package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentPlanItemStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.PlanItemVerification;
import cn.lunalhx.ai.domain.agent.model.valobj.ReplanReason;
import cn.lunalhx.ai.domain.agent.model.valobj.TodoApplyResult;
import cn.lunalhx.ai.domain.tool.model.ToolOperation;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private List<AgentPlanEvent> events = new ArrayList<>();
    private int eventSequence;

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
     *   <li><b>Update</b> — item has an {@code id} that matches an existing item
     *       by id only (content matching is no longer supported).
     *       Only the fields that are <em>explicitly provided</em> in the JSON are
     *       updated; omitted fields keep their current values.</li>
     *   <li><b>Create</b> — item has no matching {@code id} AND has non-blank
     *       {@code content}. If content duplicates an existing item, creation
     *       is rejected (use the existing item's id to update it instead).
     *       Created items require {@code kind} and, for kind=edit, non-empty
     *       {@code targets}.</li>
     * </ul>
     */
    public void applyTodoWrite(JsonNode input) {
        JsonNode todos = input == null ? null : input.path("todos");
        if (todos == null || !todos.isArray()) {
            throw new IllegalArgumentException("todos 必须是数组");
        }
        for (JsonNode todo : todos) {
            String content = todo.path("content").asText(null);
            String id = todo.path("id").asText(null);
            AgentPlanItem existing = findForUpdate(id).orElse(null);

            if (existing != null) {
                AgentPlanItemStatus beforeStatus = existing.getStatus();
                Set<String> changed = new LinkedHashSet<>();
                if (todo.has("content") && StringUtils.isNotBlank(content)) {
                    existing.setContent(content);
                    changed.add("content");
                }
                if (todo.has("status")) {
                    existing.setStatus(AgentPlanItemStatus.from(todo.path("status").asText()));
                    changed.add("status");
                }
                if (todo.has("evidence")) {
                    existing.setEvidence(todo.path("evidence").asText(existing.getEvidence()));
                    changed.add("evidence");
                }
                if (todo.has("blocker")) {
                    existing.setBlocker(todo.path("blocker").asText(existing.getBlocker()));
                    changed.add("blocker");
                }
                if (todo.has("kind")) {
                    String kind = todo.path("kind").asText(null);
                    validateKind(kind);
                    existing.setKind(kind);
                    changed.add("kind");
                }
                if (todo.path("targets").isArray()) {
                    existing.setTargets(readTargets(todo.path("targets")));
                    changed.add("targets");
                }
                if (todo.has("verification")) {
                    JsonNode v = todo.path("verification");
                    PlanItemVerification verif = PlanItemVerification.builder()
                            .command(v.path("command").asText(null))
                            .passed(v.path("passed").isNull() ? null : v.path("passed").asBoolean())
                            .exitCode(v.path("exitCode").isNull() ? null : v.path("exitCode").asInt())
                            .summary(v.path("summary").asText(null))
                            .build();
                    existing.setVerification(verif);
                    changed.add("verification");
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
                appendEvent("UPDATE", existing.getId(), "todo_write", beforeStatus,
                        existing.getStatus(), changed);

            } else if (StringUtils.isNotBlank(content)) {
                // Structured duplicate detection via dedupeKey, with exact content fallback
                String newDedupeKey = dedupeKeyFor(todo);
                Optional<AgentPlanItem> dup;
                if (newDedupeKey != null) {
                    dup = items.stream()
                            .filter(item -> {
                                String existingKey = item.getDedupeKey() != null
                                        ? item.getDedupeKey() : dedupeKeyFor(item);
                                return StringUtils.equals(existingKey, newDedupeKey);
                            })
                            .filter(item -> item.getStatus() == null || !item.getStatus().terminal())
                            .findFirst();
                    if (dup.isEmpty()) {
                        Optional<AgentPlanItem> terminalDup = items.stream()
                                .filter(item -> item.getStatus() != null && item.getStatus().terminal())
                                .filter(item -> {
                                    String existingKey = item.getDedupeKey() != null
                                            ? item.getDedupeKey() : dedupeKeyFor(item);
                                    return StringUtils.equals(existingKey, newDedupeKey);
                                })
                                .findFirst();
                        if (terminalDup.isPresent()) {
                            appendEvent("CREATE_AFTER_TERMINAL_DUPLICATE", null,
                                    "terminal_item_id=" + terminalDup.get().getId()
                                            + " dedupeKey=" + newDedupeKey, null, null, null);
                        }
                    }
                } else {
                    dup = items.stream()
                            .filter(item -> StringUtils.equals(item.getContent(), content))
                            .findFirst();
                }
                if (dup.isPresent()) {
                    appendEvent("DUPLICATE_CREATE_REJECTED", dup.get().getId(),
                            "dedupeKey=" + newDedupeKey, null, null, null);
                    throw new IllegalArgumentException(
                            "任务与已有任务(id=" + dup.get().getId() + ")重复，"
                                    + "请使用已有 id 更新，不要用近似 wording 创建重复任务");
                }
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
                PlanItemVerification verification = null;
                if (todo.has("verification")) {
                    JsonNode v = todo.path("verification");
                    verification = PlanItemVerification.builder()
                            .command(v.path("command").asText(null))
                            .passed(v.path("passed").isNull() ? null : v.path("passed").asBoolean())
                            .exitCode(v.path("exitCode").isNull() ? null : v.path("exitCode").asInt())
                            .summary(v.path("summary").asText(null))
                            .build();
                }
                AgentPlanItem created = AgentPlanItem.builder()
                        .id(id)
                        .content(content)
                        .status(status)
                        .kind(kind)
                        .targets(targets)
                        .verification(verification)
                        .evidence(todo.path("evidence").asText(null))
                        .blocker(todo.path("blocker").asText(null))
                        .dedupeKey(newDedupeKey)
                        .order(nextOrder())
                        .updateTime(Instant.now())
                        .build();
                items.add(created);
                appendEvent("CREATE", id, "todo_write", null, status, null);

            } else {
                throw new IllegalArgumentException(
                        "无法找到 id=\"" + StringUtils.defaultString(id) + "\" 的任务项进行更新，"
                                + "且未提供 content 用于创建新任务。"
                                + "请提供一个已知 id 以更新现有任务，或提供 content 创建新任务。");
            }
        }
        touch("todo_write");
    }

    /**
     * Lenient version of {@link #applyTodoWriteForReplan(JsonNode, ReplanReason, Set)} with no filtering context.
     */
    public List<TodoApplyResult> applyTodoWriteForReplan(JsonNode input) {
        return applyTodoWriteForReplan(input, null, null);
    }

    /**
     * Lenient version of {@link #applyTodoWrite} for replan dedup scenarios.
     * <ul>
     *   <li>Never throws — errors convert to {@link TodoApplyResult#skipped}</li>
     *   <li>Duplicate creates are silently ignored (DUPLICATE_CREATE_IGNORED)</li>
     *   <li>Invalid items are skipped (INVALID_DELTA_IGNORED)</li>
     *   <li>Applies ReplanDeltaPolicy filtering for scope drift, duplicate verify, irrelevant targets</li>
     *   <li>Batch-processes all items, collecting results</li>
     * </ul>
     *
     * @param input the JSON todos array
     * @param reason replan trigger reason (null to skip filtering)
     * @param touchedFiles files touched during execution (null to skip target relevance check)
     */
    public List<TodoApplyResult> applyTodoWriteForReplan(JsonNode input, ReplanReason reason, Set<String> touchedFiles) {
        List<TodoApplyResult> results = new ArrayList<>();
        JsonNode todos = input == null ? null : input.path("todos");
        if (todos == null || !todos.isArray()) {
            return results;
        }
        boolean anyApplied = false;

        for (JsonNode todo : todos) {
            try {
                String content = todo.path("content").asText(null);
                String id = todo.path("id").asText(null);
                AgentPlanItem existing = findForUpdate(id).orElse(null);

                if (existing != null) {
                    AgentPlanItemStatus beforeStatus = existing.getStatus();
                    Set<String> changed = new LinkedHashSet<>();
                    if (todo.has("content") && StringUtils.isNotBlank(content)) {
                        existing.setContent(content);
                        changed.add("content");
                    }
                    if (todo.has("status")) {
                        existing.setStatus(AgentPlanItemStatus.from(todo.path("status").asText()));
                        changed.add("status");
                    }
                    if (todo.has("evidence")) {
                        existing.setEvidence(todo.path("evidence").asText(existing.getEvidence()));
                        changed.add("evidence");
                    }
                    if (todo.has("blocker")) {
                        existing.setBlocker(todo.path("blocker").asText(existing.getBlocker()));
                        changed.add("blocker");
                    }
                    if (todo.has("kind")) {
                        String kind = todo.path("kind").asText(null);
                        if (!isValidKind(kind)) {
                            appendEvent("INVALID_DELTA_IGNORED", existing.getId(),
                                    "update_invalid_kind=" + kind, beforeStatus, beforeStatus, null);
                            results.add(TodoApplyResult.skipped(existing.getId(), "invalid"));
                            continue;
                        }
                        existing.setKind(kind);
                        changed.add("kind");
                    }
                    if (todo.path("targets").isArray()) {
                        existing.setTargets(readTargets(todo.path("targets")));
                        changed.add("targets");
                    }
                    if (todo.has("verification")) {
                        JsonNode v = todo.path("verification");
                        PlanItemVerification verif = PlanItemVerification.builder()
                                .command(v.path("command").asText(null))
                                .passed(v.path("passed").isNull() ? null : v.path("passed").asBoolean())
                                .exitCode(v.path("exitCode").isNull() ? null : v.path("exitCode").asInt())
                                .summary(v.path("summary").asText(null))
                                .build();
                        existing.setVerification(verif);
                        changed.add("verification");
                    }
                    if ("edit".equalsIgnoreCase(existing.getKind())
                            && (existing.getTargets() == null || existing.getTargets().isEmpty())) {
                        if (existing.getStatus() == null || !existing.getStatus().terminal()) {
                            appendEvent("INVALID_DELTA_IGNORED", existing.getId(),
                                    "kind=edit requires targets", existing.getStatus(), existing.getStatus(), null);
                            results.add(TodoApplyResult.skipped(existing.getId(), "invalid"));
                            continue;
                        }
                        if (existing.getTargets() == null) {
                            existing.setTargets(List.of());
                        }
                    }
                    existing.setUpdateTime(Instant.now());
                    appendEvent("UPDATE", existing.getId(), "todo_write", beforeStatus,
                            existing.getStatus(), changed);
                    results.add(TodoApplyResult.applied(existing.getId()));
                    anyApplied = true;

                } else if (StringUtils.isNotBlank(content)) {
                    String newDedupeKey = dedupeKeyFor(todo);
                    Optional<AgentPlanItem> dup;
                    if (newDedupeKey != null) {
                        dup = items.stream()
                                .filter(item -> {
                                    String existingKey = item.getDedupeKey() != null
                                            ? item.getDedupeKey() : dedupeKeyFor(item);
                                    return StringUtils.equals(existingKey, newDedupeKey);
                                })
                                .filter(item -> item.getStatus() == null || !item.getStatus().terminal())
                                .findFirst();
                        if (dup.isEmpty()) {
                            Optional<AgentPlanItem> terminalDup = items.stream()
                                    .filter(item -> item.getStatus() != null && item.getStatus().terminal())
                                    .filter(item -> {
                                        String existingKey = item.getDedupeKey() != null
                                                ? item.getDedupeKey() : dedupeKeyFor(item);
                                        return StringUtils.equals(existingKey, newDedupeKey);
                                    })
                                    .findFirst();
                            if (terminalDup.isPresent()) {
                                appendEvent("CREATE_AFTER_TERMINAL_DUPLICATE", null,
                                        "terminal_item_id=" + terminalDup.get().getId()
                                                + " dedupeKey=" + newDedupeKey, null, null, null);
                            }
                        }
                    } else {
                        dup = items.stream()
                                .filter(item -> StringUtils.equals(item.getContent(), content))
                                .findFirst();
                    }
                    if (dup.isPresent()) {
                        appendEvent("DUPLICATE_CREATE_IGNORED", dup.get().getId(),
                                "dedupeKey=" + newDedupeKey, null, null, null);
                        results.add(TodoApplyResult.skipped(dup.get().getId(), "duplicate"));
                        continue;
                    }

                    // Read kind and targets early for filtering
                    String kind = todo.path("kind").asText(null);
                    List<String> targets = readTargets(todo.path("targets"));

                    // --- ReplanDeltaPolicy filtering ---
                    if (reason != null) {

                        // Check 1: scope drift — edit targets must relate to existing plan or touched files
                        if ("edit".equalsIgnoreCase(kind) && !targets.isEmpty()) {
                            if (!checkTargetsRelevant(targets, touchedFiles)) {
                                appendEvent("REPLAN_DELTA_REJECTED_SCOPE_DRIFT", null,
                                        "targets not related to existing plan items or touched files",
                                        null, null, null);
                                results.add(TodoApplyResult.skipped(null, "scope_drift"));
                                continue;
                            }
                        }

                        // Check 2: duplicate verify — don't create verify when blocked/completed verify exists for same command
                        if ("verify".equalsIgnoreCase(kind)) {
                            boolean blockedVerifyExists = items.stream()
                                    .anyMatch(item -> "verify".equalsIgnoreCase(item.getKind())
                                            && item.getStatus() != null
                                            && (item.getStatus() == AgentPlanItemStatus.BLOCKED
                                                    || item.getStatus() == AgentPlanItemStatus.COMPLETED)
                                            && verifyDedupeMatches(item, todo));
                            if (blockedVerifyExists) {
                                appendEvent("REPLAN_DELTA_REJECTED_DUPLICATE_VERIFY", null,
                                        "duplicate verify for already blocked/completed verify",
                                        null, null, null);
                                results.add(TodoApplyResult.skipped(null, "duplicate_verify"));
                                continue;
                            }
                        }

                        // Check 3: TOOL_FAILURE — require derivedFrom or parentId for new non-inspect tasks
                        if (reason == ReplanReason.TOOL_FAILURE
                                && !"inspect".equalsIgnoreCase(kind)) {
                            String derivedFrom = todo.path("derivedFrom").asText(null);
                            String parentId = todo.path("parentId").asText(null);
                            if (StringUtils.isBlank(derivedFrom) && StringUtils.isBlank(parentId)) {
                                appendEvent("REPLAN_DELTA_REJECTED_NO_SOURCE", null,
                                        "new task requires derivedFrom or parentId for tool failure replan",
                                        null, null, null);
                                results.add(TodoApplyResult.skipped(null, "no_source"));
                                continue;
                            }
                        }
                    }

                    if (StringUtils.isBlank(id)) {
                        id = "task-" + UUID.randomUUID().toString().substring(0, 8);
                    }
                    if (!isValidKind(kind)) {
                        appendEvent("INVALID_DELTA_IGNORED", null,
                                "invalid_kind=" + kind, null, null, null);
                        results.add(TodoApplyResult.skipped(null, "invalid"));
                        continue;
                    }
                    if (!todo.has("status")) {
                        appendEvent("INVALID_DELTA_IGNORED", null,
                                "new item requires status", null, null, null);
                        results.add(TodoApplyResult.skipped(null, "invalid"));
                        continue;
                    }
                    AgentPlanItemStatus status =
                            AgentPlanItemStatus.from(todo.path("status").asText());
                    if ("edit".equalsIgnoreCase(kind) && targets.isEmpty()) {
                        appendEvent("INVALID_DELTA_IGNORED", null,
                                "kind=edit requires non-empty targets", null, null, null);
                        results.add(TodoApplyResult.skipped(null, "invalid"));
                        continue;
                    }
                    PlanItemVerification verification = null;
                    if (todo.has("verification")) {
                        JsonNode v = todo.path("verification");
                        verification = PlanItemVerification.builder()
                                .command(v.path("command").asText(null))
                                .passed(v.path("passed").isNull() ? null : v.path("passed").asBoolean())
                                .exitCode(v.path("exitCode").isNull() ? null : v.path("exitCode").asInt())
                                .summary(v.path("summary").asText(null))
                                .build();
                    }
                    AgentPlanItem created = AgentPlanItem.builder()
                            .id(id)
                            .content(content)
                            .status(status)
                            .kind(kind)
                            .targets(targets)
                            .verification(verification)
                            .evidence(todo.path("evidence").asText(null))
                            .blocker(todo.path("blocker").asText(null))
                            .dedupeKey(newDedupeKey)
                            .derivedFrom(todo.path("derivedFrom").asText(null))
                            .parentId(todo.path("parentId").asText(null))
                            .order(nextOrder())
                            .updateTime(Instant.now())
                            .build();
                    items.add(created);
                    appendEvent("CREATE", id, "todo_write", null, status, null);
                    results.add(TodoApplyResult.applied(id));
                    anyApplied = true;

                } else {
                    appendEvent("INVALID_DELTA_IGNORED", null, "no_id_or_content",
                            null, null, null);
                    results.add(TodoApplyResult.skipped(null, "no_id_or_content"));
                }
            } catch (Exception e) {
                appendEvent("INVALID_DELTA_IGNORED", null, "error: " + e.getMessage(),
                        null, null, null);
                results.add(TodoApplyResult.skipped(null, "error"));
            }
        }

        if (anyApplied) {
            touch("todo_write");
        }
        return results;
    }

    public void addReplanItem(String content, String reason) {
        addReplanItem(content, reason, null);
    }

    public void addReplanItem(String content, String reason, String kind) {
        String newDedupeKey = content != null ? "content:" + normalizedContentKey(content) : null;
        boolean isDuplicate = newDedupeKey != null && items.stream().anyMatch(item -> {
            String existingKey = item.getDedupeKey() != null
                    ? item.getDedupeKey() : dedupeKeyFor(item);
            return StringUtils.equals(existingKey, newDedupeKey);
        });
        if (!isDuplicate) {
            addItem(content, AgentPlanItemStatus.PENDING, kind);
            AgentPlanItem newItem = items.get(items.size() - 1);
            newItem.setDedupeKey(newDedupeKey);
            String newId = newItem.getId();
            appendEvent("REPLAN_APPEND", newId, reason, null, AgentPlanItemStatus.PENDING, null);
        } else {
            appendEvent("REPLAN_DEDUPED", null, reason, null, null, null);
        }
        touch(reason);
    }

    /**
     * Mark an item as BLOCKED with a given blocker reason.
     */
    public void blockItem(String itemId, String blocker) {
        Optional<AgentPlanItem> item = items.stream()
                .filter(i -> StringUtils.equals(i.getId(), itemId))
                .findFirst();
        if (item.isPresent()) {
            AgentPlanItemStatus before = item.get().getStatus();
            item.get().setStatus(AgentPlanItemStatus.BLOCKED);
            item.get().setBlocker(blocker);
            item.get().setUpdateTime(Instant.now());
            appendEvent("BLOCKED", itemId, "replan_convergence", before,
                    AgentPlanItemStatus.BLOCKED, Set.of("status", "blocker"));
        }
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

    /**
     * Check if path is covered by the currently active (in_progress) edit item.
     */
    public boolean hasActiveEditTarget(String path) {
        AgentPlanItem active = activeEditItem();
        if (active == null || active.getTargets() == null) {
            return false;
        }
        String normalized = ToolOperation.normalizePath(path);
        return active.getTargets().stream()
                .map(ToolOperation::normalizePath)
                .anyMatch(normalized::equals);
    }

    /**
     * Returns the targets of the currently active edit item, or empty list.
     */
    public List<String> currentEditableTargets() {
        AgentPlanItem active = activeEditItem();
        if (active == null || active.getTargets() == null) {
            return List.of();
        }
        return active.getTargets();
    }

    /**
     * Returns the active edit item: the one currently in_progress,
     * or the unique incomplete edit item if none is explicitly in_progress.
     * Returns null if there are zero or multiple incomplete edit candidates.
     */
    public AgentPlanItem activeEditItem() {
        // Prefer the explicit in_progress edit item
        Optional<AgentPlanItem> inProgress = items.stream()
                .filter(item -> "edit".equalsIgnoreCase(item.getKind()))
                .filter(item -> item.getStatus() == AgentPlanItemStatus.IN_PROGRESS)
                .findFirst();
        if (inProgress.isPresent()) {
            return inProgress.get();
        }
        // Fallback: unique incomplete edit item
        List<AgentPlanItem> candidates = items.stream()
                .filter(item -> "edit".equalsIgnoreCase(item.getKind()))
                .filter(AgentPlanItem::incomplete)
                .collect(Collectors.toList());
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    public boolean hasVerifyItem() {
        return items.stream().anyMatch(item ->
                "verify".equalsIgnoreCase(item.getKind()));
    }

    /**
     * Find the current in_progress verify item, or the unique incomplete verify item.
     */
    public AgentPlanItem activeVerifyItem() {
        Optional<AgentPlanItem> inProgress = items.stream()
                .filter(item -> "verify".equalsIgnoreCase(item.getKind()))
                .filter(item -> item.getStatus() == AgentPlanItemStatus.IN_PROGRESS)
                .findFirst();
        if (inProgress.isPresent()) {
            return inProgress.get();
        }
        List<AgentPlanItem> candidates = items.stream()
                .filter(item -> "verify".equalsIgnoreCase(item.getKind()))
                .filter(AgentPlanItem::incomplete)
                .collect(Collectors.toList());
        return candidates.size() == 1 ? candidates.get(0) : null;
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
        if (!events.isEmpty()) {
            int from = Math.max(0, events.size() - 20);
            List<Map<String, Object>> recent = new ArrayList<>();
            for (int i = from; i < events.size(); i++) {
                recent.add(eventToView(events.get(i)));
            }
            view.put("recentEvents", recent);
        }
        return view;
    }

    private Map<String, Object> eventToView(AgentPlanEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sequence", e.getSequence());
        m.put("type", e.getType());
        m.put("itemId", e.getItemId());
        m.put("reason", e.getReason());
        m.put("timestamp", e.getTimestamp() != null ? e.getTimestamp().toString() : null);
        if (e.getBeforeStatus() != null) m.put("beforeStatus", e.getBeforeStatus().code());
        if (e.getAfterStatus() != null) m.put("afterStatus", e.getAfterStatus().code());
        if (e.getChangedFields() != null) m.put("changedFields", e.getChangedFields());
        return m;
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

    /**
     * Find item by id only. Content matching is no longer supported for updates.
     */
    private Optional<AgentPlanItem> findForUpdate(String id) {
        if (StringUtils.isBlank(id)) {
            return Optional.empty();
        }
        return items.stream()
                .filter(item -> StringUtils.equals(item.getId(), id))
                .findFirst();
    }

    private void appendEvent(String type, String itemId, String reason,
                             AgentPlanItemStatus beforeStatus, AgentPlanItemStatus afterStatus,
                             Set<String> changedFields) {
        eventSequence++;
        events.add(AgentPlanEvent.builder()
                .sequence(eventSequence)
                .type(type)
                .itemId(itemId)
                .reason(reason)
                .timestamp(Instant.now())
                .beforeStatus(beforeStatus)
                .afterStatus(afterStatus)
                .changedFields(changedFields)
                .build());
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
        if (!isValidKind(kind)) {
            throw new IllegalArgumentException("kind 只能是 inspect、edit 或 verify");
        }
    }

    private boolean isValidKind(String kind) {
        return StringUtils.isNotBlank(kind)
                && List.of("inspect", "edit", "verify").contains(kind);
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

    private String dedupeKeyFor(JsonNode todo) {
        String kind = todo.path("kind").asText(null);
        List<String> targets = readTargets(todo.path("targets"));

        if ("edit".equals(kind) && !targets.isEmpty()) {
            return "edit:" + String.join("|", targets);
        }
        if ("inspect".equals(kind) && !targets.isEmpty()) {
            return "inspect:" + String.join("|", targets);
        }
        if ("verify".equals(kind)) {
            String verifyCmd = todo.has("verification")
                    ? todo.path("verification").path("command").asText(null) : null;
            if (StringUtils.isNotBlank(verifyCmd)) {
                return "verify:" + verifyCmd.trim();
            }
        }

        String content = todo.path("content").asText(null);
        if (StringUtils.isNotBlank(content)) {
            return "content:" + normalizedContentKey(content);
        }

        return null;
    }

    private String dedupeKeyFor(AgentPlanItem item) {
        if (item.getDedupeKey() != null) {
            return item.getDedupeKey();
        }

        String kind = item.getKind();
        List<String> targets = item.getTargets();

        if ("edit".equals(kind) && targets != null && !targets.isEmpty()) {
            return "edit:" + String.join("|", targets);
        }
        if ("inspect".equals(kind) && targets != null && !targets.isEmpty()) {
            return "inspect:" + String.join("|", targets);
        }
        if ("verify".equals(kind)) {
            PlanItemVerification verification = item.getVerification();
            if (verification != null && StringUtils.isNotBlank(verification.getCommand())) {
                return "verify:" + verification.getCommand().trim();
            }
        }

        String content = item.getContent();
        if (StringUtils.isNotBlank(content)) {
            return "content:" + normalizedContentKey(content);
        }

        return null;
    }

    private String normalizedContentKey(String content) {
        if (StringUtils.isBlank(content)) {
            return "";
        }
        String key = content.trim();
        key = key.replaceAll("(文件|任务|步骤)\\s*$", "");
        key = key.replaceAll("\\s+", " ");
        return key.trim();
    }

    /**
     * Check if at least one of the new targets is related to existing plan items or touched files.
     */
    private boolean checkTargetsRelevant(List<String> newTargets, Set<String> touchedFiles) {
        if (newTargets == null || newTargets.isEmpty()) {
            return true;
        }
        Set<String> knownTargets = new LinkedHashSet<>();
        for (AgentPlanItem item : items) {
            if (item.getTargets() != null) {
                for (String t : item.getTargets()) {
                    knownTargets.add(ToolOperation.normalizePath(t));
                }
            }
        }
        if (touchedFiles != null) {
            for (String f : touchedFiles) {
                knownTargets.add(ToolOperation.normalizePath(f));
            }
        }
        if (knownTargets.isEmpty()) {
            return true; // no existing context to validate against
        }
        for (String t : newTargets) {
            if (knownTargets.contains(ToolOperation.normalizePath(t))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if two target lists have any overlap (at least one path in common).
     */
    private boolean targetsOverlap(List<String> targetsA, List<String> targetsB) {
        if (targetsA == null || targetsA.isEmpty() || targetsB == null || targetsB.isEmpty()) {
            return false;
        }
        Set<String> setA = new LinkedHashSet<>();
        for (String t : targetsA) {
            setA.add(ToolOperation.normalizePath(t));
        }
        for (String t : targetsB) {
            if (setA.contains(ToolOperation.normalizePath(t))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a todo JSON node for a verify item matches an existing plan item by dedupe key.
     */
    private boolean verifyDedupeMatches(AgentPlanItem item, JsonNode todo) {
        String existingKey = item.getDedupeKey() != null
                ? item.getDedupeKey() : dedupeKeyFor(item);
        String newKey = dedupeKeyFor(todo);
        return StringUtils.isNotBlank(existingKey) && StringUtils.equals(existingKey, newKey);
    }

}
