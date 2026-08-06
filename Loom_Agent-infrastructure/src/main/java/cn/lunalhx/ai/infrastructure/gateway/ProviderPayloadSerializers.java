package cn.lunalhx.ai.infrastructure.gateway;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatMessage;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.valobj.PromptCacheCapability;
import cn.lunalhx.ai.domain.model.valobj.PromptCacheRequest;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider payload serializers. Infrastructure owns the OpenAI/Anthropic JSON
 * field details; the domain never sees them.
 *
 * <p>Each provider keeps its structured message boundaries: the stable prefix
 * stays in the {@code system} role, the dynamic sections (workspace snapshot,
 * memory, transcript, current request) stay as separate user messages. There
 * is no transport-level unconditional {@code flattenPrompt()}.
 *
 * <p>Prompt-cache fields are written only when the request is enabled and the
 * provider capability supports them. Unknown providers never receive cache
 * fields.
 */
final class ProviderPayloadSerializers {

    private ProviderPayloadSerializers() {
    }

    /** OpenAI Responses protocol: {@code prompt_cache_key}/{@code prompt_cache_retention}. */
    static Map<String, Object> openAIResponses(ChatPrompt prompt, String model, int maxTokens,
                                               Double temperature, Double topP,
                                               PromptCacheRequest cacheRequest) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        List<Map<String, Object>> input = new ArrayList<>();
        if (prompt.getSystemPrompt() != null && !prompt.getSystemPrompt().isBlank()) {
            input.add(Map.of(
                    "role", "system",
                    "content", List.of(Map.of("type", "input_text", "text", prompt.getSystemPrompt()))));
        }
        for (ChatMessage message : prompt.getMessages()) {
            if (message != null && message.getContent() != null && !message.getContent().isBlank()) {
                String role = "assistant".equals(message.getRole()) ? "assistant" : "user";
                input.add(Map.of(
                        "role", role,
                        "content", List.of(Map.of("type", "input_text", "text", message.getContent()))));
            }
        }
        payload.put("input", input);
        payload.put("max_output_tokens", maxTokens);
        payload.put("stream", false);
        if (temperature != null) {
            payload.put("temperature", temperature);
        }
        if (topP != null) {
            payload.put("top_p", topP);
        }
        if (cacheRequest != null && cacheRequest.enabled()
                && cacheRequest.getCapability() == PromptCacheCapability.KEYED_REQUEST
                && cacheRequest.getCacheKey() != null) {
            payload.put("prompt_cache_key", cacheRequest.getCacheKey());
            if (cacheRequest.getRetention() != null) {
                payload.put("prompt_cache_retention", cacheRequest.getRetention());
            }
        }
        return payload;
    }

    /** Anthropic Messages protocol. cache-control content blocks are only used
     *  when the capability is MESSAGE_BLOCK; otherwise plain text blocks. */
    static Map<String, Object> anthropicMessages(ChatPrompt prompt, String model, int maxTokens,
                                                 Double temperature, Double topP,
                                                 PromptCacheRequest cacheRequest) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("max_tokens", maxTokens);
        payload.put("stream", false);
        if (temperature != null) {
            payload.put("temperature", temperature);
        }
        if (topP != null) {
            payload.put("top_p", topP);
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        if (prompt.getSystemPrompt() != null && !prompt.getSystemPrompt().isBlank()) {
            payload.put("system", List.of(Map.of("type", "text", "text", prompt.getSystemPrompt())));
        }
        boolean cacheBlocks = cacheRequest != null && cacheRequest.enabled()
                && cacheRequest.getCapability() == PromptCacheCapability.MESSAGE_BLOCK;
        for (ChatMessage message : prompt.getMessages()) {
            if (message != null && message.getContent() != null && !message.getContent().isBlank()) {
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("type", "text");
                content.put("text", message.getContent());
                List<Map<String, Object>> blocks = new ArrayList<>();
                blocks.add(content);
                if (cacheBlocks) {
                    blocks.add(Map.of("type", "cache_control", "cache_control", Map.of("type", "ephemeral")));
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("role", "assistant".equals(message.getRole()) ? "assistant" : "user");
                entry.put("content", blocks);
                messages.add(entry);
            }
        }
        if (messages.isEmpty()) {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("type", "text");
            content.put("text", "");
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", "user");
            entry.put("content", List.of(content));
            messages.add(entry);
        }
        payload.put("messages", messages);
        return payload;
    }

    /** Ollama stays unsupported for prompt cache; single user prompt text. */
    static Map<String, Object> ollama(ChatPrompt prompt, String model, int maxTokens,
                                      Double temperature, Double topP) {
        String text = plainText(prompt);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("num_predict", maxTokens);
        if (temperature != null) {
            options.put("temperature", temperature);
        }
        if (topP != null) {
            options.put("top_p", topP);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("prompt", text);
        payload.put("stream", false);
        payload.put("raw", false);
        payload.put("think", false);
        payload.put("options", options);
        return payload;
    }

    /** Plain-text rendering used only by providers without structured roles. */
    static String plainText(ChatPrompt prompt) {
        StringBuilder sb = new StringBuilder();
        if (prompt.getSystemPrompt() != null && !prompt.getSystemPrompt().isBlank()) {
            sb.append(prompt.getSystemPrompt()).append("\n\n");
        }
        if (prompt.getMessages() != null) {
            for (ChatMessage message : prompt.getMessages()) {
                if (message != null && message.getContent() != null && !message.getContent().isBlank()) {
                    sb.append(message.getContent()).append("\n\n");
                }
            }
        }
        if (prompt.getMessage() != null && !prompt.getMessage().isBlank()) {
            sb.append(prompt.getMessage());
        }
        return sb.toString().strip();
    }

    /** Resolve the cache request from the prompt + runtime properties + flag. */
    static PromptCacheRequest resolveCacheRequest(ChatPrompt prompt, ModelRuntimeProperties properties,
                                                  boolean featureEnabled) {
        return resolveCacheRequest(prompt, properties, featureEnabled, null);
    }

    static PromptCacheRequest resolveCacheRequest(ChatPrompt prompt, ModelRuntimeProperties properties,
                                                  boolean featureEnabled, String providerOverride) {
        if (providerOverride == null) {
            return cn.lunalhx.ai.domain.model.service.PromptCacheKeyFactory.buildRequest(
                    prompt, properties, featureEnabled);
        }
        return cn.lunalhx.ai.domain.model.service.PromptCacheKeyFactory.buildRequestWithProvider(
                prompt, providerOverride, featureEnabled);
    }

}
