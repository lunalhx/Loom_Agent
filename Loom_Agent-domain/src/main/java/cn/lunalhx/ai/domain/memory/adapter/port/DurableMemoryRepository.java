package cn.lunalhx.ai.domain.memory.adapter.port;

import cn.lunalhx.ai.domain.memory.model.MemoryEntry;

import java.util.List;
import java.util.Optional;

/**
 * Workspace-scoped durable memory store ({@code .loom-code/memory/}). Each
 * workspace is fully isolated; the store is scoped to one workspace root.
 */
public interface DurableMemoryRepository {

    MemoryEntry save(MemoryEntry entry);

    /** Replace the existing entry for topic+subject (new conclusion wins). */
    MemoryEntry upsert(MemoryEntry entry);

    Optional<MemoryEntry> findByTopicAndSubject(String topic, String subject);

    List<MemoryEntry> findByTopic(String topic);

    List<MemoryEntry> findAll();

    /** Recent first; used by the dynamic context selection. */
    List<MemoryEntry> findAllNewestFirst();

    void delete(String id);
}
