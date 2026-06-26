package cn.lunalhx.ai.domain.agent.adapter.port;

import cn.lunalhx.ai.domain.agent.model.entity.SubAgentControlMessage;

import java.util.List;

public interface SubAgentControlInbox {

    void send(String childRunId, SubAgentControlMessage message);

    List<SubAgentControlMessage> poll(String childRunId);

    void clear(String childRunId);
}
