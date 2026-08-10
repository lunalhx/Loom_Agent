package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable, resumable session state. Replaces the CLI's loose
 * {@code Map<String,Object>} session and is the single source of truth for
 * history, working memory and the latest semantic checkpoint.
 *
 * <p>A session spans many root runs (one per user question). Runs reference
 * the session by {@code sessionId} and never resume an old run's node
 * execution position — only task semantics and context are restored.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSession {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    private String id;
    private Integer schemaVersion;
    private String workspaceRoot;
    private CollaborationMode collaborationMode;
    private Instant createdAt;
    private Instant updatedAt;

    /** Append-only conversation ledger persisted across runs. */
    @Builder.Default
    private List<ConversationHistoryEntry> history = new ArrayList<>();

    private long ledgerNextSequence;

    /** Working memory (task summary, recent files, file summaries, notes). */
    private WorkingContextMemory workingMemory;

    /** Latest semantic task checkpoint (TaskCheckpoint semantics). */
    private TaskCheckpoint checkpoint;

    /** Path → SHA-256 of key files the session depends on. */
    @Builder.Default
    private Map<String, String> keyFiles = new LinkedHashMap<>();

    private String runtimeIdentity;
}
