package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.memory.model.entity.MemoryExtractionPayload;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryType;
import cn.lunalhx.ai.domain.memory.service.MemoryExtractionService;
import cn.lunalhx.ai.domain.memory.service.MemoryExtractionService.ExtractionResult;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class MemoryExtractionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void shouldExtractValidMemories() {
        AtomicReference<Double> capturedTemp = new AtomicReference<>();
        ModelGateway gateway = completeGateway("{\"memories\":["
                + "{\"type\":\"PREFERENCE\",\"title\":\"Uses tabs\",\"summary\":\"Prefers tabs\",\"body\":\"User prefers tabs over spaces.\",\"importance\":80},"
                + "{\"type\":\"PROJECT\",\"title\":\"Java project\",\"summary\":\"This is a Java project\",\"body\":\"The project uses Maven and Java 21.\",\"importance\":60}"
                + "]}", capturedTemp);

        MemoryExtractionService service = new MemoryExtractionService(gateway, objectMapper, null);
        MemoryExtractionPayload payload = new MemoryExtractionPayload("What is Java?", "Java is a language.", 5, "/tmp/test");

        ExtractionResult result = service.extract(payload, System.currentTimeMillis() + 60000);

        assertTrue(result.isSuccess());
        assertEquals(2, result.memories().size());
        assertEquals(MemoryType.PREFERENCE, result.memories().get(0).type());
        assertEquals("Uses tabs", result.memories().get(0).title());
        assertEquals(80, result.memories().get(0).importance());
        assertEquals(MemoryType.PROJECT, result.memories().get(1).type());
        assertNotNull(result.memories().get(0).contentHash());
        assertEquals(0.0, capturedTemp.get(), 0.001);
    }

    @Test
    public void shouldReturnEmptyForEmptyArray() {
        ModelGateway gateway = completeGateway("{\"memories\":[]}", null);

        MemoryExtractionService service = new MemoryExtractionService(gateway, objectMapper, null);
        ExtractionResult result = service.extract(
                new MemoryExtractionPayload("q", "a", 1, "/tmp"), System.currentTimeMillis() + 60000);

        assertTrue(result.isEmpty());
        assertFalse(result.retryable());
        assertTrue(result.memories().isEmpty());
    }

    @Test
    public void shouldStripMarkdownFences() {
        ModelGateway gateway = completeGateway("```json\n{\"memories\":[{\"type\":\"PREFERENCE\",\"title\":\"T\",\"summary\":\"S\",\"body\":\"B\",\"importance\":50}]}\n```", null);

        MemoryExtractionService service = new MemoryExtractionService(gateway, objectMapper, null);
        ExtractionResult result = service.extract(
                new MemoryExtractionPayload("q", "a", 1, "/tmp"), System.currentTimeMillis() + 60000);

        assertTrue(result.isSuccess());
        assertEquals(1, result.memories().size());
    }

    @Test
    public void shouldRetryOnInvalidJson() {
        ModelGateway gateway = completeGateway("not valid json at all", null);

        MemoryExtractionService service = new MemoryExtractionService(gateway, objectMapper, null);
        ExtractionResult result = service.extract(
                new MemoryExtractionPayload("q", "a", 1, "/tmp"), System.currentTimeMillis() + 60000);

        assertTrue(result.retryable());
        assertNotNull(result.errorMessage());
    }

    @Test
    public void shouldRetryOnMissingMemoriesField() {
        ModelGateway gateway = completeGateway("{\"other\":[]}", null);

        MemoryExtractionService service = new MemoryExtractionService(gateway, objectMapper, null);
        ExtractionResult result = service.extract(
                new MemoryExtractionPayload("q", "a", 1, "/tmp"), System.currentTimeMillis() + 60000);

        assertTrue(result.retryable());
    }

    @Test
    public void shouldRetryOnInvalidType() {
        ModelGateway gateway = completeGateway("{\"memories\":[{\"type\":\"INVALID_TYPE\",\"title\":\"T\",\"body\":\"B\"}]}", null);

        MemoryExtractionService service = new MemoryExtractionService(gateway, objectMapper, null);
        ExtractionResult result = service.extract(
                new MemoryExtractionPayload("q", "a", 1, "/tmp"), System.currentTimeMillis() + 60000);

        assertTrue(result.retryable());
    }

    @Test
    public void shouldRetryOnMissingTitle() {
        ModelGateway gateway = completeGateway("{\"memories\":[{\"type\":\"PREFERENCE\",\"body\":\"B\"}]}", null);

        MemoryExtractionService service = new MemoryExtractionService(gateway, objectMapper, null);
        ExtractionResult result = service.extract(
                new MemoryExtractionPayload("q", "a", 1, "/tmp"), System.currentTimeMillis() + 60000);

        assertTrue(result.retryable());
    }

    @Test
    public void shouldRetryOnMissingBody() {
        ModelGateway gateway = completeGateway("{\"memories\":[{\"type\":\"PREFERENCE\",\"title\":\"T\"}]}", null);

        MemoryExtractionService service = new MemoryExtractionService(gateway, objectMapper, null);
        ExtractionResult result = service.extract(
                new MemoryExtractionPayload("q", "a", 1, "/tmp"), System.currentTimeMillis() + 60000);

        assertTrue(result.retryable());
    }

    @Test
    public void shouldRetryOnEmptyResponse() {
        ModelGateway gateway = completeGateway("", null);

        MemoryExtractionService service = new MemoryExtractionService(gateway, objectMapper, null);
        ExtractionResult result = service.extract(
                new MemoryExtractionPayload("q", "a", 1, "/tmp"), System.currentTimeMillis() + 60000);

        assertTrue(result.retryable());
    }

    @Test
    public void shouldRetryOnNullResponse() {
        ModelGateway gateway = completeGateway(null, null);

        MemoryExtractionService service = new MemoryExtractionService(gateway, objectMapper, null);
        ExtractionResult result = service.extract(
                new MemoryExtractionPayload("q", "a", 1, "/tmp"), System.currentTimeMillis() + 60000);

        assertTrue(result.retryable());
    }

    @Test
    public void shouldRetryOnModelException() {
        ModelGateway gateway = errorGateway(new RuntimeException("API error"));

        MemoryExtractionService service = new MemoryExtractionService(gateway, objectMapper, null);
        ExtractionResult result = service.extract(
                new MemoryExtractionPayload("q", "a", 1, "/tmp"), System.currentTimeMillis() + 60000);

        assertTrue(result.retryable());
        assertTrue(result.errorMessage().contains("API error"));
    }

    @Test
    public void shouldClampImportance() {
        ModelGateway gateway = completeGateway("{\"memories\":["
                + "{\"type\":\"PREFERENCE\",\"title\":\"T\",\"summary\":\"S\",\"body\":\"B\",\"importance\":150},"
                + "{\"type\":\"PREFERENCE\",\"title\":\"T2\",\"summary\":\"S2\",\"body\":\"B2\",\"importance\":-10}"
                + "]}", null);

        MemoryExtractionService service = new MemoryExtractionService(gateway, objectMapper, null);
        ExtractionResult result = service.extract(
                new MemoryExtractionPayload("q", "a", 1, "/tmp"), System.currentTimeMillis() + 60000);

        assertTrue(result.isSuccess());
        assertEquals(100, result.memories().get(0).importance());
        assertEquals(0, result.memories().get(1).importance());
    }

    @Test
    public void shouldTruncateFields() {
        String longTitle = "A".repeat(500);
        String longBody = "B".repeat(15000);
        ModelGateway gateway = completeGateway("{\"memories\":[{\"type\":\"PREFERENCE\",\"title\":\"" + longTitle
                + "\",\"summary\":\"" + "S".repeat(1000) + "\",\"body\":\"" + longBody + "\",\"importance\":50}]}", null);

        MemoryExtractionService service = new MemoryExtractionService(gateway, objectMapper, null);
        ExtractionResult result = service.extract(
                new MemoryExtractionPayload("q", "a", 1, "/tmp"), System.currentTimeMillis() + 60000);

        assertTrue(result.isSuccess());
        assertEquals(200, result.memories().get(0).title().length());
        assertEquals(500, result.memories().get(0).summary().length());
        assertEquals(10000, result.memories().get(0).body().length());
    }

    @Test
    public void shouldTruncateToMaxFiveMemories() {
        StringBuilder json = new StringBuilder("{\"memories\":[");
        for (int i = 0; i < 8; i++) {
            if (i > 0) json.append(",");
            json.append("{\"type\":\"PREFERENCE\",\"title\":\"T").append(i)
                    .append("\",\"summary\":\"S\",\"body\":\"B\",\"importance\":50}");
        }
        json.append("]}");

        ModelGateway gateway = completeGateway(json.toString(), null);

        MemoryExtractionService service = new MemoryExtractionService(gateway, objectMapper, null);
        ExtractionResult result = service.extract(
                new MemoryExtractionPayload("q", "a", 1, "/tmp"), System.currentTimeMillis() + 60000);

        assertTrue(result.isSuccess());
        assertEquals(5, result.memories().size());
    }

    @Test
    public void shouldAcceptAllValidTypes() {
        for (MemoryType type : MemoryType.values()) {
            ModelGateway gateway = completeGateway("{\"memories\":[{\"type\":\"" + type.name()
                    + "\",\"title\":\"T\",\"summary\":\"S\",\"body\":\"B\",\"importance\":50}]}", null);

            MemoryExtractionService service = new MemoryExtractionService(gateway, objectMapper, null);
            ExtractionResult result = service.extract(
                    new MemoryExtractionPayload("q", "a", 1, "/tmp"), System.currentTimeMillis() + 60000);

            assertTrue("Type " + type + " should be valid", result.isSuccess());
            assertEquals(type, result.memories().get(0).type());
        }
    }

    @Test
    public void shouldStripPlainFence() {
        ModelGateway gateway = completeGateway("```\n{\"memories\":[{\"type\":\"PREFERENCE\",\"title\":\"T\",\"summary\":\"S\",\"body\":\"B\",\"importance\":50}]}\n```", null);

        MemoryExtractionService service = new MemoryExtractionService(gateway, objectMapper, null);
        ExtractionResult result = service.extract(
                new MemoryExtractionPayload("q", "a", 1, "/tmp"), System.currentTimeMillis() + 60000);

        assertTrue("Should strip plain ``` fences", result.isSuccess());
        assertEquals(1, result.memories().size());
    }

    @Test
    public void shouldUseProvidedModel() {
        AtomicReference<String> capturedModel = new AtomicReference<>();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }
            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                capturedModel.set(prompt.getModel());
                return Mono.just(ModelChatResult.builder()
                        .content("{\"memories\":[]}")
                        .finishReason("stop")
                        .build());
            }
        };

        MemoryExtractionService service = new MemoryExtractionService(gateway, objectMapper, "gpt-4o");
        service.extract(new MemoryExtractionPayload("q", "a", 1, "/tmp"), System.currentTimeMillis() + 60000);

        assertEquals("gpt-4o", capturedModel.get());
    }

    @Test
    public void shouldNotSetModelWhenNull() {
        AtomicReference<String> capturedModel = new AtomicReference<>("SENTINEL");
        ModelGateway gateway = new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }
            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                capturedModel.set(prompt.getModel());
                return Mono.just(ModelChatResult.builder()
                        .content("{\"memories\":[]}")
                        .finishReason("stop")
                        .build());
            }
        };

        MemoryExtractionService service = new MemoryExtractionService(gateway, objectMapper, null);
        service.extract(new MemoryExtractionPayload("q", "a", 1, "/tmp"), System.currentTimeMillis() + 60000);

        assertNull(capturedModel.get());
    }

    private static ModelGateway completeGateway(String content, AtomicReference<Double> tempCapture) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }
            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                if (tempCapture != null) {
                    tempCapture.set(prompt.getTemperature());
                }
                return Mono.just(ModelChatResult.builder()
                        .content(content)
                        .finishReason("stop")
                        .build());
            }
        };
    }

    private static ModelGateway errorGateway(RuntimeException ex) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }
            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.error(ex);
            }
        };
    }
}
