package cn.lunalhx.ai.domain.memory.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A durable memory entry promoted from a finished agent run. Fixed four
 * topics: project conventions, key decisions, dependency facts, user
 * preferences. A new conclusion for the same topic+subject replaces the old
 * entry.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryEntry {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    private Integer schemaVersion = CURRENT_SCHEMA_VERSION;
    private String id;
    private String topic;
    private String subject;
    private String content;
    private String sourceRunId;
    private Instant createdAt;
    private Instant updatedAt;
}
