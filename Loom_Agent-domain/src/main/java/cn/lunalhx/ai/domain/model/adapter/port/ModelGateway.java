package cn.lunalhx.ai.domain.model.adapter.port;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import reactor.core.publisher.Flux;

public interface ModelGateway {

    Flux<ModelStreamChunk> stream(ChatPrompt prompt);

}
