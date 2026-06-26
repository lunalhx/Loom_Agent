package cn.lunalhx.ai.infrastructure.adapter.port;

import cn.lunalhx.ai.domain.agent.adapter.port.SubAgentControlInbox;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentControlMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class InMemorySubAgentControlInbox implements SubAgentControlInbox {

    private final ConcurrentMap<String, List<SubAgentControlMessage>> store = new ConcurrentHashMap<>();

    @Override
    public void send(String childRunId, SubAgentControlMessage message) {
        if (childRunId == null || childRunId.isBlank() || message == null) {
            return;
        }
        store.computeIfAbsent(childRunId, k -> new CopyOnWriteArrayList<>()).add(message);
    }

    @Override
    public List<SubAgentControlMessage> poll(String childRunId) {
        List<SubAgentControlMessage> messages = store.getOrDefault(childRunId, List.of());
        long now = System.currentTimeMillis();
        List<SubAgentControlMessage> active = new ArrayList<>();
        for (SubAgentControlMessage m : messages) {
            if (m.getDeadlineMs() > now) {
                active.add(m);
            }
        }
        return active;
    }

    @Override
    public void clear(String childRunId) {
        store.remove(childRunId);
    }
}
