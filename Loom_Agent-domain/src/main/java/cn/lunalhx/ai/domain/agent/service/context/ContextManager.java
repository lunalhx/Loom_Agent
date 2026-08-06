package cn.lunalhx.ai.domain.agent.service.context;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistory;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryEntry;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ConversationEntryType;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextProperties;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public facade for building a temporary, section-budgeted send view of the
 * model context each round, mirroring loom-code's {@code ContextManager}.
 *
 * <p>Fixed section order: {@code prefix → memory → relevant_memory → history →
 * current_request}. The raw {@code ConversationHistory} is NEVER mutated; every
 * build produces an immutable {@link ContextBuildResult}.
 *
 * <p>The four dynamic sections are always emitted as four {@code user} messages
 * with stable {@code Memory:}/{@code Relevant memory:}/{@code Transcript:}/
 * {@code Current user request:} headers; the {@code prefix} is the single
 * system prompt. Section budgets (chars) and floors default to loom-code values
 * and are configured via {@code ContextProperties}. When
 * {@code contextReductionEnabled} is false, sections are sent without trimming
 * (current request still last).
 */
public final class ContextManager {

    private static final List<String> SECTION_ORDER =
            List.of("prefix", "memory", "relevant_memory", "history", "current_request");

    private final AgentRuntimeProperties properties;
    private final HistoryRenderer historyRenderer;
    private final RelevantMemorySelector relevantSelector;
    private final DurableMemorySelector durableSelector;

    public ContextManager(AgentRuntimeProperties properties) {
        this(properties, null);
    }

    /** @param durableMemoryProvider workspace-scoped durable memory entries,
     *                              newest first; null disables the section. */
    public ContextManager(AgentRuntimeProperties properties,
                          java.util.function.Supplier<List<cn.lunalhx.ai.domain.memory.model.MemoryEntry>> durableMemoryProvider) {
        this.properties = properties == null ? new AgentRuntimeProperties() : properties;
        this.historyRenderer = new HistoryRenderer();
        this.relevantSelector = new RelevantMemorySelector();
        this.durableSelector = new DurableMemorySelector(durableMemoryProvider);
    }

    public ContextBuildResult build(AgentContext context) {
        ContextProperties cfg = contextProperties(context);
        boolean reductionEnabled = Boolean.TRUE.equals(cfg.getContextReductionEnabled());

        Sections raw = renderSections(context, cfg);
        if (!reductionEnabled) {
            return assembleRaw(raw, context);
        }
        return assembleBudgeted(raw, context, cfg);
    }

    /** Budget estimation text used by {@code BudgetMiddleware}. */
    public String budgetInput(AgentContext context) {
        return build(context).budgetText();
    }

    /**
     * Build a floor-pressed view for the single overflow retry: all four
     * trimmed sections are forced to their floors and the current request is
     * preserved (never trimmed). Used when the provider still reports
     * context overflow under the normal budgeted view.
     */
    public ContextBuildResult buildFloorPressed(AgentContext context) {
        ContextProperties cfg = contextProperties(context);
        Sections raw = renderSections(context, cfg);

        int prefixFloor = positive(cfg.getPrefixFloorChars(), 900);
        int memoryFloor = positive(cfg.getMemoryFloorChars(), 400);
        int relevantFloor = positive(cfg.getRelevantMemoryFloorChars(), 300);
        int historyFloor = positive(cfg.getHistoryFloorChars(), 1300);

        String trimmedPrefix = TextUtil.clipHeadTail(raw.prefix, prefixFloor);
        String trimmedMemory = TextUtil.clipHeadTail(raw.memory, memoryFloor);
        String trimmedRelevant = TextUtil.clipHeadTail(raw.relevantMemory, relevantFloor);
        String trimmedHistory = TextUtil.clipHeadTail(raw.historyText, historyFloor);

        int total = TextUtil.length(trimmedPrefix) + TextUtil.length(trimmedMemory)
                + TextUtil.length(trimmedRelevant) + TextUtil.length(trimmedHistory)
                + TextUtil.length(raw.currentRequest);

        Map<String, Integer> rawChars = sectionRawChars(raw);
        Map<String, Integer> budget = sectionBudgetChars(cfg);
        Map<String, Integer> rendered = new LinkedHashMap<>();
        rendered.put("prefix", TextUtil.length(trimmedPrefix));
        rendered.put("memory", TextUtil.length(trimmedMemory));
        rendered.put("relevant_memory", TextUtil.length(trimmedRelevant));
        rendered.put("history", TextUtil.length(trimmedHistory));
        rendered.put("current_request", TextUtil.length(raw.currentRequest));

        ContextBuildResult.ContextRenderMetadata metadata =
                new ContextBuildResult.ContextRenderMetadata(
                        total, positive(cfg.getTotalBudgetChars(), 12000), false,
                        rawChars, budget, rendered, SECTION_ORDER,
                        List.of("floor_pressure"), true,
                        raw.historyResult.merged(), raw.historyResult.summarized(),
                        raw.historyResult.deduped(), raw.historyResult.summaryReuse(),
                        raw.relevantSelected,
                        raw.currentRequest, true, TextUtil.length(raw.currentRequest));

        List<ChatMessage> messages = messages(
                trimmedMemory, trimmedRelevant, trimmedHistory, raw.currentRequest);
        return new ContextBuildResult(trimmedPrefix, messages, metadata, false, null);
    }

    // ================================================================
    // Section rendering
    // ================================================================

    private Sections renderSections(AgentContext context, ContextProperties cfg) {
        String prefix = renderPrefix(context);
        String memory = renderMemory(context);
        int currentRequestIndex = currentRequestEntryIndex(context);
        String currentRequest = renderCurrentRequest(context);
        boolean reductionEnabled = Boolean.TRUE.equals(cfg.getContextReductionEnabled());
        List<RelevantMemorySelector.ScoredNote> selected = relevantSelector.select(
                context.getWorkingMemory(), currentRequest,
                positive(cfg.getRelevantMemoryLimit(), 3));
        int relevantBudget = positive(cfg.getRelevantMemoryBudgetChars(), 1200);
        List<DurableMemorySelector.ScoredEntry> durable = durableSelector.select(
                currentRequest,
                positive(cfg.getRelevantMemoryLimit(), 3),
                Math.max(200, relevantBudget / 2));
        String relevantMemory = renderRelevantMemory(selected, durable, relevantBudget);
        int historyBudget = reductionEnabled
                ? positive(cfg.getHistoryBudgetChars(), 5200) : Integer.MAX_VALUE;
        HistoryRenderer.HistoryResult historyResult = historyRenderer.render(
                context.getConversationHistory(), currentRequestIndex,
                positive(cfg.getRecentHistoryItems(), 6),
                historyBudget,
                context.getWorkingMemory(), reductionEnabled);
        return new Sections(prefix, memory, relevantMemory, historyResult.text(),
                currentRequest, historyResult, selected.size() + durable.size());
    }

    private String renderPrefix(AgentContext context) {
        StablePrefix stablePrefix = context.getStablePrefix();
        return stablePrefix == null ? "" : stablePrefix.frozenContent();
    }

    private String renderMemory(AgentContext context) {
        WorkingContextMemory wm = context.getWorkingMemory();
        if (wm == null || wm.isEmpty()) {
            return "Memory: - none";
        }
        StringBuilder sb = new StringBuilder("Memory:\n");
        if (wm.taskSummary() != null) {
            sb.append("- task: ").append(wm.taskSummary()).append('\n');
        }
        if (!wm.recentFiles().isEmpty()) {
            sb.append("- recent files: ").append(String.join(", ", wm.recentFiles())).append('\n');
        }
        for (WorkingContextMemory.FileSummary fs : wm.fileSummaries().values()) {
            sb.append("- file: ").append(fs.path()).append('\n')
                    .append("  summary: ").append(fs.summary()).append('\n');
        }
        if (!wm.notes().isEmpty()) {
            sb.append("- note count: ").append(wm.notes().size()).append('\n');
        }
        return sb.toString();
    }

    private String renderRelevantMemory(List<RelevantMemorySelector.ScoredNote> selected,
                                        List<DurableMemorySelector.ScoredEntry> durable,
                                        int budgetChars) {
        if ((selected == null || selected.isEmpty()) && (durable == null || durable.isEmpty())) {
            return "Relevant memory: - none";
        }
        int durableChars = 0;
        for (DurableMemorySelector.ScoredEntry d : durable) {
            durableChars += d.entry().getContent().length() + d.entry().getTopic().length() + 12;
        }
        int workingChars = Math.max(1, budgetChars - durableChars);
        int perNote = Math.max(1, workingChars / Math.max(1, selected.size()));
        StringBuilder sb = new StringBuilder("Relevant memory:\n");
        for (RelevantMemorySelector.ScoredNote scored : selected) {
            sb.append("- ").append(TextUtil.head(scored.note().text(), perNote)).append('\n');
        }
        for (DurableMemorySelector.ScoredEntry d : durable) {
            sb.append("- durable[").append(d.entry().getTopic()).append("] ")
                    .append(d.entry().getSubject()).append(": ")
                    .append(TextUtil.head(d.entry().getContent(), 300)).append('\n');
        }
        return sb.toString();
    }

    private String renderCurrentRequest(AgentContext context) {
        ConversationHistory history = context.getConversationHistory();
        if (history == null || history.isEmpty()) {
            return prefixTitle("Current user request:", context.getQuestion());
        }
        int idx = currentRequestEntryIndex(context);
        if (idx >= 0) {
            return prefixTitle("Current user request:", history.entries().get(idx).content());
        }
        return prefixTitle("Current user request:", context.getQuestion());
    }

    private String prefixTitle(String title, String body) {
        return title + "\n" + (body == null ? "" : body);
    }

    private int currentRequestEntryIndex(AgentContext context) {
        ConversationHistory history = context.getConversationHistory();
        if (history == null || history.isEmpty()) {
            return -1;
        }
        List<ConversationHistoryEntry> entries = history.entries();
        for (int i = entries.size() - 1; i >= 0; i--) {
            ConversationHistoryEntry entry = entries.get(i);
            if (entry.stableType() == ConversationEntryType.USER_TASK
                    || entry.stableType() == ConversationEntryType.USER_INPUT) {
                return i;
            }
        }
        return -1;
    }

    // ================================================================
    // Assembly
    // ================================================================

    private List<ChatMessage> messages(String memory, String relevant, String history, String currentRequest) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.builder().role("user").content(memory).build());
        messages.add(ChatMessage.builder().role("user").content(relevant).build());
        messages.add(ChatMessage.builder().role("user").content(history).build());
        messages.add(ChatMessage.builder().role("user").content(currentRequest).build());
        return messages;
    }

    private ContextBuildResult assembleRaw(Sections raw, AgentContext context) {
        List<ChatMessage> messages = messages(raw.memory, raw.relevantMemory, raw.historyText, raw.currentRequest);
        int total = TextUtil.length(raw.prefix) + TextUtil.length(raw.memory)
                + TextUtil.length(raw.relevantMemory) + TextUtil.length(raw.historyText)
                + TextUtil.length(raw.currentRequest);

        ContextBuildResult.ContextRenderMetadata metadata =
                new ContextBuildResult.ContextRenderMetadata(
                        total, 0, false,
                        sectionRawChars(raw), Map.of(), sectionRawChars(raw), SECTION_ORDER,
                        List.of(), false,
                        raw.historyResult.merged(), raw.historyResult.summarized(),
                        raw.historyResult.deduped(), raw.historyResult.summaryReuse(),
                        raw.relevantSelected,
                        raw.currentRequest, true, TextUtil.length(raw.currentRequest));
        return new ContextBuildResult(raw.prefix, messages, metadata, false, null);
    }

    private ContextBuildResult assembleBudgeted(Sections raw, AgentContext context, ContextProperties cfg) {
        int totalBudget = positive(cfg.getTotalBudgetChars(), 12000);
        int prefixBudget = positive(cfg.getPrefixBudgetChars(), 3600);
        int prefixFloor = positive(cfg.getPrefixFloorChars(), 900);
        int memoryBudget = positive(cfg.getMemoryBudgetChars(), 1600);
        int memoryFloor = positive(cfg.getMemoryFloorChars(), 400);
        int relevantBudget = positive(cfg.getRelevantMemoryBudgetChars(), 1200);
        int relevantFloor = positive(cfg.getRelevantMemoryFloorChars(), 300);
        int historyBudget = positive(cfg.getHistoryBudgetChars(), 5200);
        int historyFloor = positive(cfg.getHistoryFloorChars(), 1300);

        // Current request is always preserved and never trimmed.
        int currentRequestChars = TextUtil.length(raw.currentRequest);

        String trimmedPrefix = TextUtil.clipHeadTail(raw.prefix, prefixBudget);
        String trimmedMemory = TextUtil.clipHeadTail(raw.memory, memoryBudget);
        String trimmedRelevant = TextUtil.clipHeadTail(raw.relevantMemory, relevantBudget);
        String trimmedHistory = TextUtil.clipHeadTail(raw.historyText, historyBudget);

        int total = totalLength(trimmedPrefix, trimmedMemory, trimmedRelevant, trimmedHistory, currentRequestChars);

        List<String> reductions = new ArrayList<>();
        // If still over budget, reduce in fixed order: relevant_memory → history → memory → prefix,
        // each pressed to its floor, re-rendering after each change.
        if (total > totalBudget && TextUtil.length(trimmedRelevant) > relevantFloor) {
            trimmedRelevant = TextUtil.clipHeadTail(trimmedRelevant, relevantFloor);
            reductions.add("relevant_memory->floor");
            total = totalLength(trimmedPrefix, trimmedMemory, trimmedRelevant, trimmedHistory, currentRequestChars);
        }
        if (total > totalBudget && TextUtil.length(trimmedHistory) > historyFloor) {
            trimmedHistory = TextUtil.clipHeadTail(trimmedHistory, historyFloor);
            reductions.add("history->floor");
            total = totalLength(trimmedPrefix, trimmedMemory, trimmedRelevant, trimmedHistory, currentRequestChars);
        }
        if (total > totalBudget && TextUtil.length(trimmedMemory) > memoryFloor) {
            trimmedMemory = TextUtil.clipHeadTail(trimmedMemory, memoryFloor);
            reductions.add("memory->floor");
            total = totalLength(trimmedPrefix, trimmedMemory, trimmedRelevant, trimmedHistory, currentRequestChars);
        }
        if (total > totalBudget && TextUtil.length(trimmedPrefix) > prefixFloor) {
            trimmedPrefix = TextUtil.clipHeadTail(trimmedPrefix, prefixFloor);
            reductions.add("prefix->floor");
            total = totalLength(trimmedPrefix, trimmedMemory, trimmedRelevant, trimmedHistory, currentRequestChars);
        }

        List<ChatMessage> messages = messages(
                trimmedMemory, trimmedRelevant, trimmedHistory, raw.currentRequest);

        Map<String, Integer> budget = sectionBudgetChars(cfg);
        Map<String, Integer> rendered = new LinkedHashMap<>();
        rendered.put("prefix", TextUtil.length(trimmedPrefix));
        rendered.put("memory", TextUtil.length(trimmedMemory));
        rendered.put("relevant_memory", TextUtil.length(trimmedRelevant));
        rendered.put("history", TextUtil.length(trimmedHistory));
        rendered.put("current_request", currentRequestChars);

        ContextBuildResult.ContextRenderMetadata metadata =
                new ContextBuildResult.ContextRenderMetadata(
                        total, totalBudget, total > totalBudget,
                        sectionRawChars(raw), budget, rendered, SECTION_ORDER, reductions, true,
                        raw.historyResult.merged(), raw.historyResult.summarized(),
                        raw.historyResult.deduped(), raw.historyResult.summaryReuse(),
                        raw.relevantSelected,
                        raw.currentRequest, true, currentRequestChars);
        return new ContextBuildResult(trimmedPrefix, messages, metadata, false, null);
    }

    private Map<String, Integer> sectionRawChars(Sections raw) {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("prefix", TextUtil.length(raw.prefix));
        map.put("memory", TextUtil.length(raw.memory));
        map.put("relevant_memory", TextUtil.length(raw.relevantMemory));
        map.put("history", TextUtil.length(raw.historyText));
        map.put("current_request", TextUtil.length(raw.currentRequest));
        return map;
    }

    private Map<String, Integer> sectionBudgetChars(ContextProperties cfg) {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("prefix", positive(cfg.getPrefixBudgetChars(), 3600));
        map.put("memory", positive(cfg.getMemoryBudgetChars(), 1600));
        map.put("relevant_memory", positive(cfg.getRelevantMemoryBudgetChars(), 1200));
        map.put("history", positive(cfg.getHistoryBudgetChars(), 5200));
        map.put("current_request", 0);
        return map;
    }

    private int totalLength(String prefix, String memory, String relevant, String history, int request) {
        return TextUtil.length(prefix) + TextUtil.length(memory)
                + TextUtil.length(relevant) + TextUtil.length(history) + request;
    }

    private ContextProperties contextProperties(AgentContext context) {
        AgentRuntimeProperties effective = context.runtimeProperties(properties);
        if (effective == null || effective.getContext() == null) {
            return new ContextProperties();
        }
        return effective.getContext();
    }

    private int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    /** Raw, pre-budget section texts plus render diagnostics. */
    private record Sections(String prefix, String memory, String relevantMemory,
                            String historyText, String currentRequest,
                            HistoryRenderer.HistoryResult historyResult, int relevantSelected) {
    }
}
