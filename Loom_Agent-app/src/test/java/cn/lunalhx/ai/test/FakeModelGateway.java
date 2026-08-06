package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic scripted gateway for offline evaluation. The script maps a
 * prompt hash to a fixed decision, so the same task always produces the same
 * run — no network, no flakiness. Records per-call usage for the metrics.
 */
public final class FakeModelGateway implements ModelGateway {

    private final List<String> script;
    private final AtomicInteger calls = new AtomicInteger();
    private final CopyOnWriteArrayList<ModelChatResult> responses = new CopyOnWriteArrayList<>();

    public FakeModelGateway(List<String> decisions) {
        this.script = List.copyOf(decisions);
    }

    public static FakeModelGateway finalAnswer(String answer) {
        return new FakeModelGateway(List.of("<final>" + answer + "</final>"));
    }

    @Override
    public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
        return Flux.empty();
    }

    @Override
    public Mono<ModelChatResult> complete(ChatPrompt prompt) {
        int call = calls.getAndIncrement();
        int idx = Math.max(0, Math.min(call, script.size() - 1));
        String content = script.isEmpty() ? "<final>(empty script)</final>" : script.get(idx);
        ModelChatResult result = ModelChatResult.builder()
                .content(content)
                .finishReason("stop")
                .actualModel("deepseek-v4-flash")
                .usage(TokenUsage.builder()
                        .promptTokens(100 + idx * 10)
                        .completionTokens(50)
                        .totalTokens(150 + idx * 10)
                        .build())
                .build();
        responses.add(result);
        return Mono.just(result);
    }

    public int callCount() {
        return calls.get();
    }

    public List<ModelChatResult> responses() {
        return List.copyOf(responses);
    }
}
