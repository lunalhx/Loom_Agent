package cn.lunalhx.ai.infrastructure.gateway;

import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.service.ModelCallTraceContext;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelCapability;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.infrastructure.gateway.resilience.ModelCallKey;
import cn.lunalhx.ai.infrastructure.gateway.resilience.ModelRequestNormalizer;
import cn.lunalhx.ai.infrastructure.gateway.resilience.CompleteModelCallExecutor;
import cn.lunalhx.ai.infrastructure.gateway.resilience.StreamingModelCallExecutor;
import cn.lunalhx.ai.infrastructure.gateway.resilience.ModelResilienceAssembly;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Primary
@Component
public class ResilientModelGateway implements ModelGateway {

    private final ModelGateway delegate;
    private final ModelRuntimeProperties properties;
    private final ModelRequestNormalizer requestNormalizer;
    private final CompleteModelCallExecutor completeExecutor;
    private final StreamingModelCallExecutor streamingExecutor;

    @Autowired
    public ResilientModelGateway(@Qualifier("deepSeekModelGateway") ModelGateway delegate,
                                 ModelRuntimeProperties properties,
                                 TraceRecorder traceRecorder,
                                 MeterRegistry meterRegistry,
                                 Environment environment,
                                 BudgetGuard budgetGuard) {
        this.delegate = delegate;
        this.properties = properties;
        ModelResilienceAssembly assembly = ModelResilienceAssembly.create(
                delegate, properties, traceRecorder, meterRegistry, environment, budgetGuard);
        this.requestNormalizer = assembly.requestNormalizer();
        this.completeExecutor = assembly.completeExecutor();
        this.streamingExecutor = assembly.streamingExecutor();
    }

    public ResilientModelGateway(ModelGateway delegate,
                                 ModelRuntimeProperties properties,
                                 TraceRecorder traceRecorder,
                                 MeterRegistry meterRegistry,
                                 Environment environment) {
        this(delegate, properties, traceRecorder, meterRegistry, environment, null);
    }

    @Override
    public Mono<ModelChatResult> complete(ChatPrompt prompt) {
        ChatPrompt normalized = requestNormalizer.normalizeComplete(prompt);
        ModelCallKey key = requestNormalizer.key(normalized);
        AgentContext context = ModelCallTraceContext.current();
        if (!enabled()) {
            return delegate.complete(normalized);
        }
        return completeExecutor.execute(normalized, key, context);
    }

    @Override
    public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
        ChatPrompt normalized = requestNormalizer.normalizeStream(prompt);
        ModelCallKey key = requestNormalizer.key(normalized);
        AgentContext context = ModelCallTraceContext.current();
        if (!enabled()) {
            return delegate.stream(normalized);
        }
        return streamingExecutor.execute(normalized, key, context);
    }

    @Override
    public ModelCapability capability(String model) {
        return requestNormalizer.capability(model);
    }

    private boolean enabled() {
        ModelRuntimeProperties.ResilienceProperties resilience = properties.getResilience();
        if (resilience == null) {
            return true;
        }
        return Boolean.TRUE.equals(resilience.getEnabled());
    }

}
