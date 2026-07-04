package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.memory.model.entity.MemoryExtractionPayload;
import cn.lunalhx.ai.domain.memory.service.MemoryExtractionService;
import cn.lunalhx.ai.domain.memory.service.MemoryExtractionService.ExtractionResult;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryTraceRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * memory_extraction 是后台 worker 调起的，本身没有真实 Agent 上下文；
 * 这里验证它通过 TraceRecorder 写入 model_usage 事件时能保留 hit/miss 字段和
 * 我们约定的低基数 metadata。
 */
public class MemoryExtractionTraceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void traceMetadataShouldNotContainHighCardinalityKeys() {
        InMemoryTraceRecorder traceRecorder = new InMemoryTraceRecorder();
        ModelGateway gateway = completeGateway("{\"memories\":[]}", null, null);

        MemoryExtractionService service = new MemoryExtractionService(
                gateway, objectMapper, null, traceRecorder);
        service.extract(new MemoryExtractionPayload("q", "a", 1, "/tmp"),
                System.currentTimeMillis() + 60000);

        AgentTraceEvent usage = findModelUsage(allEvents(traceRecorder));
        assertNotNull(usage);
        for (String forbidden : new String[]{"runId", "traceId", "requestId",
                "conversationId", "workspace", "userId", "question"}) {
            assertFalse("metadata 不应包含高基数 key: " + forbidden,
                    usage.getMetadata().containsKey(forbidden));
        }
    }

    // ===== helpers =====

    private static ModelGateway completeGateway(String content, String actualModel, TokenUsage usage) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.just(ModelChatResult.builder()
                        .content(content)
                        .finishReason("stop")
                        .actualModel(actualModel)
                        .usage(usage)
                        .build());
            }
        };
    }

    private static AgentTraceEvent findModelUsage(List<AgentTraceEvent> events) {
        for (AgentTraceEvent event : events) {
            if ("model_usage".equals(event.getEventType())) {
                return event;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<AgentTraceEvent> allEvents(InMemoryTraceRecorder traceRecorder) {
        try {
            java.lang.reflect.Field field = InMemoryTraceRecorder.class.getDeclaredField("events");
            field.setAccessible(true);
            Object map = field.get(traceRecorder);
            if (!(map instanceof Map<?, ?> m)) {
                return List.of();
            }
            List<AgentTraceEvent> all = new ArrayList<>();
            for (Object list : m.values()) {
                if (list instanceof Collection<?> c) {
                    for (Object e : c) {
                        if (e instanceof AgentTraceEvent) {
                            all.add((AgentTraceEvent) e);
                        }
                    }
                }
            }
            return all;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
