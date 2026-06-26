package cn.lunalhx.ai.infrastructure.adapter.port;

import cn.lunalhx.ai.domain.agent.adapter.port.SubAgentControlInbox;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentControlMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

public class InMemorySubAgentControlInbox implements SubAgentControlInbox {

    private final ConcurrentMap<String, List<SubAgentControlMessage>> store = new ConcurrentHashMap<>();

    @Override
    public void send(String childRunId, SubAgentControlMessage message) {
        store.computeIfAbsent(childRunId, k -> new ArrayList<>()).add(message);
    }

    @Override
    public List<SubAgentControlMessage> poll(String childRunId) {
        List<SubAgentControlMessage> messages = store.getOrDefault(childRunId, List.of());
        return messages.stream()
                .filter(m -> m.getDeadlineMs() > System.currentTimeMillis())
                .collect(Collectors.toList());
    }

    @Override
    public void clear(String childRunId) {
        store.remove(childRunId);
    }
}
