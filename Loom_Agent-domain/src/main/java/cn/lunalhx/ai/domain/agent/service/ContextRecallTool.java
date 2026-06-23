package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ContextRecallTool implements AgentTool {

    public static final String NAME = "context_recall";

    private final ContextArtifactRepository artifactRepository;
    private final ContextBlobStore blobStore;

    public ContextRecallTool(ContextArtifactRepository artifactRepository, ContextBlobStore blobStore) {
        this.artifactRepository = artifactRepository;
        this.blobStore = blobStore;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name(NAME)
                .description("Recall compressed context artifacts for the current root run only. Supports list/search/get.")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"action\":{\"enum\":[\"list\",\"search\",\"get\"]},\"artifactId\":{\"type\":\"string\"},\"query\":{\"type\":\"string\"},\"offset\":{\"type\":\"integer\"},\"maxChars\":{\"type\":\"integer\"},\"limit\":{\"type\":\"integer\"}},\"required\":[\"action\"]}")
                .build();
    }

    @Override
    public ToolPolicyDecision policy(ToolCall call) {
        return ToolPolicyDecision.readOnly("只读回取当前 run 的上下文 artifact", NAME);
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        if (call == null || StringUtils.isBlank(call.getRootRunId())) {
            return ToolResult.failure("context_recall_scope_missing", "context_recall 缺少 rootRunId，无法限定读取范围", elapsed(startedAt));
        }
        JsonNode input = call.getInput();
        String action = input == null ? "" : StringUtils.lowerCase(input.path("action").asText(""));
        return switch (action) {
            case "list" -> list(call, input, startedAt);
            case "search" -> search(call, input, startedAt);
            case "get" -> get(call, input, startedAt);
            default -> ToolResult.failure("context_recall_bad_action", "action 只能是 list/search/get", elapsed(startedAt));
        };
    }

    private ToolResult list(ToolCall call, JsonNode input, long startedAt) {
        int limit = positive(input == null ? 50 : input.path("limit").asInt(50), 50);
        List<ContextArtifact> artifacts = artifactRepository.listByRootRunId(call.getRootRunId());
        StringBuilder text = new StringBuilder();
        text.append("artifacts:\n");
        artifacts.stream().limit(limit).forEach(artifact -> appendSummary(text, artifact));
        return ToolResult.success(text.toString(), false, elapsed(startedAt));
    }

    private ToolResult search(ToolCall call, JsonNode input, long startedAt) {
        String query = input == null ? "" : input.path("query").asText("");
        if (StringUtils.isBlank(query)) {
            return ToolResult.failure("context_recall_query_required", "search 需要 query", elapsed(startedAt));
        }
        int limit = positive(input.path("limit").asInt(20), 20);
        List<ContextArtifact> metadataMatches = new ArrayList<>(artifactRepository.searchByRootRunId(call.getRootRunId(), query, limit));
        String needle = StringUtils.lowerCase(query);
        for (ContextArtifact artifact : artifactRepository.listByRootRunId(call.getRootRunId())) {
            if (metadataMatches.size() >= limit || metadataMatches.stream().anyMatch(existing -> StringUtils.equals(existing.getArtifactId(), artifact.getArtifactId()))) {
                continue;
            }
            String body = blobStore.read(artifact.getStorageUri());
            if (StringUtils.contains(StringUtils.lowerCase(body), needle)) {
                metadataMatches.add(artifact);
            }
        }
        StringBuilder text = new StringBuilder();
        text.append("matches:\n");
        metadataMatches.stream().limit(limit).forEach(artifact -> appendSummary(text, artifact));
        return ToolResult.success(text.toString(), false, elapsed(startedAt));
    }

    private ToolResult get(ToolCall call, JsonNode input, long startedAt) {
        String artifactId = input == null ? "" : input.path("artifactId").asText("");
        if (StringUtils.isBlank(artifactId)) {
            return ToolResult.failure("context_recall_artifact_required", "get 需要 artifactId", elapsed(startedAt));
        }
        ContextArtifact artifact = artifactRepository.findByArtifactIdAndRootRunId(artifactId, call.getRootRunId()).orElse(null);
        if (artifact == null) {
            return ToolResult.failure("context_recall_not_found", "当前 root run 下不存在该 artifact", elapsed(startedAt));
        }
        String body = blobStore.read(artifact.getStorageUri());
        int offset = Math.max(0, input.path("offset").asInt(0));
        int maxChars = Math.min(20000, positive(input.path("maxChars").asInt(8000), 8000));
        int start = Math.min(offset, StringUtils.length(body));
        int end = Math.min(StringUtils.length(body), start + maxChars);
        String page = body.substring(start, end);
        String text = "artifactId=" + artifact.getArtifactId() + "\n"
                + "kind=" + artifact.getKind().name().toLowerCase(Locale.ROOT) + "\n"
                + "offset=" + start + "\n"
                + "returnedChars=" + page.length() + "\n"
                + "totalChars=" + StringUtils.length(body) + "\n"
                + "sha256=" + artifact.getSha256() + "\n"
                + "content:\n" + page;
        return ToolResult.success(text, end < StringUtils.length(body), elapsed(startedAt));
    }

    private void appendSummary(StringBuilder text, ContextArtifact artifact) {
        text.append("- artifactId=").append(artifact.getArtifactId())
                .append(" kind=").append(artifact.getKind().name().toLowerCase(Locale.ROOT))
                .append(" runId=").append(artifact.getRunId())
                .append(" originalChars=").append(artifact.getOriginalChars())
                .append(" retainedChars=").append(artifact.getRetainedChars())
                .append(" sha256=").append(artifact.getSha256())
                .append("\n  preview=").append(StringUtils.replace(artifact.getPreview(), "\n", "\\n"))
                .append('\n');
    }

    private int positive(int value, int fallback) {
        return value <= 0 ? fallback : value;
    }

    private long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }

}
