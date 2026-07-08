package cn.lunalhx.ai.infrastructure.adapter.embedding;

import cn.lunalhx.ai.domain.memory.adapter.port.MemoryEmbeddingGateway;
import cn.lunalhx.ai.domain.memory.model.valobj.EmbeddingVector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class OpenAiCompatibleEmbeddingGateway implements MemoryEmbeddingGateway {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingGateway.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int dimensions;
    private final int timeoutMs;
    private final int batchSize;

    public OpenAiCompatibleEmbeddingGateway(String baseUrl, String apiKey, String model,
                                            int dimensions, int timeoutMs, int batchSize) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
        this.timeoutMs = timeoutMs;
        this.batchSize = batchSize;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    @Override
    public EmbeddingVector embed(String text) {
        List<EmbeddingVector> results = embedBatch(List.of(text));
        if (results.isEmpty()) {
            throw new RuntimeException("embedding returned empty result");
        }
        return results.get(0);
    }

    @Override
    public List<EmbeddingVector> embedBatch(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        try {
            String requestBody = buildRequestBody(texts);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/embeddings"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("embedding API returned status {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("embedding API error: " + response.statusCode());
            }

            List<EmbeddingVector> results = parseResponse(response.body());
            String loggedModel = results.isEmpty() ? model : results.get(0).model();
            log.info("embedded {} texts using model {} at {} dimensions", texts.size(), loggedModel, dimensions);
            return results;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.warn("embedding call failed", e);
            throw new RuntimeException("embedding call failed", e);
        }
    }

    private String buildRequestBody(List<String> texts) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"input\":[");
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escapeJson(texts.get(i))).append('"');
        }
        sb.append("],\"model\":\"").append(escapeJson(model)).append('"');
        sb.append(",\"encoding_format\":\"float\"");
        sb.append(",\"dimensions\":").append(dimensions);
        sb.append('}');
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private List<EmbeddingVector> parseResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String responseModel = root.path("model").asText(model);
            JsonNode data = root.path("data");
            if (data.isMissingNode() || !data.isArray()) {
                throw new RuntimeException("missing or invalid 'data' field in embedding response");
            }
            List<EmbeddingVector> results = new ArrayList<>(data.size());
            for (JsonNode item : data) {
                JsonNode embeddingNode = item.path("embedding");
                if (embeddingNode.isMissingNode() || !embeddingNode.isArray()) {
                    throw new RuntimeException("missing or invalid 'embedding' field in response item");
                }
                int len = embeddingNode.size();
                if (len != dimensions) {
                    throw new RuntimeException("embedding dimension mismatch: expected " + dimensions
                            + ", got " + len);
                }
                float[] values = new float[len];
                for (int i = 0; i < len; i++) {
                    values[i] = (float) embeddingNode.get(i).asDouble();
                }
                results.add(new EmbeddingVector(values, responseModel, dimensions));
            }
            return results;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("failed to parse embedding response", e);
        }
    }

    public static String formatEmbeddingText(String type, String title, String summary, String body) {
        return "Type: " + type + "\n"
                + "Title: " + title + "\n"
                + "Summary: " + summary + "\n"
                + "Body:\n"
                + body;
    }
}
