package cn.lunalhx.ai.infrastructure.adapter.port;

import cn.lunalhx.ai.domain.agent.adapter.port.SubAgentControlInbox;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentControlMessage;
import cn.lunalhx.ai.domain.agent.model.valobj.MemoryStoreProperties;
import com.google.common.cache.CacheBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public class InMemorySubAgentControlInbox implements SubAgentControlInbox {

    private final ConcurrentMap<String, List<SubAgentControlMessage>> store;
    private final int maxMessagesPerRun;

    public InMemorySubAgentControlInbox() {
        this.store = new ConcurrentHashMap<>();
        this.maxMessagesPerRun = Integer.MAX_VALUE;
    }

    public InMemorySubAgentControlInbox(MemoryStoreProperties props) {
        this.store = CacheBuilder.newBuilder()
                .maximumSize(props.getMaxSubAgentInboxes())
                .expireAfterAccess(props.getTtlSeconds(), TimeUnit.SECONDS)
                .<String, List<SubAgentControlMessage>>build()
                .asMap();
        this.maxMessagesPerRun = props.getMaxSubAgentMessagesPerRun();
    }

    @Override
    public void send(String childRunId, SubAgentControlMessage message) {
        if (childRunId == null || childRunId.isBlank() || message == null) {
            return;
        }
        List<SubAgentControlMessage> messages = store.computeIfAbsent(childRunId,
                k -> new CopyOnWriteArrayList<>());
        while (messages.size() >= maxMessagesPerRun) {
            messages.remove(0);
        }
        messages.add(message);
    }

    @Override
    public List<SubAgentControlMessage> poll(String childRunId) {
        if (childRunId == null || childRunId.isBlank()) {
            return List.of();
        }
        List<SubAgentControlMessage> messages = store.get(childRunId);
        if (messages == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<SubAgentControlMessage> active = new ArrayList<>();
        for (SubAgentControlMessage m : messages) {
            if (m.getDeadlineMs() > now) {
                active.add(m);
            }
        }
        if (active.isEmpty()) {
            store.remove(childRunId);
        } else if (active.size() < messages.size()) {
            store.put(childRunId, new CopyOnWriteArrayList<>(active));
        }
        return active;
    }

    @Override
    public void clear(String childRunId) {
        store.remove(childRunId);
    }
}
