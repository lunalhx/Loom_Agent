package cn.lunalhx.ai.domain.model.adapter.port;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelCapability;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ModelGateway {

    Flux<ModelStreamChunk> stream(ChatPrompt prompt);

    default Mono<ModelChatResult> complete(ChatPrompt prompt) {
        return Mono.error(new UnsupportedOperationException("complete is not implemented"));
    }

    default ModelCapability capability(String model) {
        return null;
    }

}
