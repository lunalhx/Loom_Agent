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
 * Semantic task anchor replacing the old execution-position checkpoint.
 *
 * <p>It captures what the task is about, what is done, what blocks it, and the
 * next step — not the node execution position. Key files carry SHA-256 so a
 * workspace change can invalidate the summary and mark a partial resume.
 *
 * <p>Schema is versioned; older or unknown schemas are rejected by the
 * session loader (no automatic migration, no dual-format compatibility).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskCheckpoint {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    private Integer schemaVersion;
    private String sessionId;
    private String runId;
    private String goal;
    private String completed;
    private String excluded;
    private String blocker;
    private String nextStep;
    private String summary;
    private String runtimeIdentity;
    private CollaborationMode runModeSnapshot;

    /** Path → SHA-256 of the key files this checkpoint depends on. */
    @Builder.Default
    private Map<String, String> keyFiles = new LinkedHashMap<>();

    /** Durable working memory captured with the checkpoint. */
    private WorkingContextMemory workingMemory;

    @Builder.Default
    private List<ConversationHistoryEntry> history = new ArrayList<>();

    private long ledgerNextSequence;

    private Instant createdAt;
    private Instant updatedAt;
}
