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

    /** Provider cache capability: UNSUPPORTED / KEYED_REQUEST / MESSAGE_BLOCK.
     *  Never inferred from prompt prefix similarity — declared per provider/model. */
    default String cacheCapability() {
        return "unsupported";
    }

    /** Provider cache capability enum view (defaults to unsupported). */
    default cn.lunalhx.ai.domain.model.valobj.PromptCacheCapability promptCacheCapability() {
        return cn.lunalhx.ai.domain.model.valobj.PromptCacheCapability.UNSUPPORTED;
    }

}
