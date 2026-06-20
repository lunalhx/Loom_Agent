package cn.lunalhx.ai.domain.conversation.service;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.StreamEvent;
import reactor.core.publisher.Flux;

public interface ChatStreamService {

    Flux<StreamEvent> stream(ChatPrompt prompt);

}
