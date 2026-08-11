package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.domain.tool.model.ApprovalRequirement;
import cn.lunalhx.ai.domain.tool.model.EvidenceObservationType;
import cn.lunalhx.ai.domain.tool.model.EvidenceRevalidation;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolCapabilityEnvelope;
import cn.lunalhx.ai.domain.tool.model.ToolEvidenceCandidate;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/** loom-code {@code search}: complete deterministic ripgrep observations. */
@Component
public class SearchTool implements AgentTool {

    private final WorkspacePort workspacePort;
    private final SearchObservationService observationService;

    public SearchTool(WorkspacePort workspacePort) {
        this.workspacePort = workspacePort;
        this.observationService = new SearchObservationService();
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("search")
                .description("Search the workspace with the exact ripgrep engine semantics.")
                .inputSchema("{" +
                        "\"type\":\"object\"," +
                        "\"properties\":{" +
                        "\"pattern\":{\"type\":\"string\",\"minLength\":1,\"description\":\"search pattern\"}," +
                        "\"path\":{\"type\":\"string\",\"default\":\".\",\"description\":\"search path\"}" +
                        "}," +
                        "\"required\":[\"pattern\"]," +
                        "\"additionalProperties\":false" +
                        "}")
                .capabilityEnvelope(ToolCapabilityEnvelope.repositoryRead())
                .approvalRequirement(ApprovalRequirement.NONE)
                .build();
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        try {
            String pattern = text(call, "pattern", null);
            String rawPath = text(call, "path", ".");
            if (pattern == null || pattern.isBlank()) {
                return failure("pattern must not be empty", startedAt);
            }
            Path root = LoomToolSupport.root(workspacePort, call);
            Path scope = LoomToolSupport.resolve(workspacePort, call, rawPath);
            SearchObservationService.Observation observation =
                    observationService.observe(root, scope, pattern);
            String normalizedScope = observation.searchScope();
            ToolResult result = ToolResult.success(observation.render(), false, elapsed(startedAt));
            result.setEvidenceCandidate(ToolEvidenceCandidate.builder()
                    .evidenceKey("search|" + normalizedScope + "|query="
                            + observation.normalizedQuery() + "|engine=" + observation.engineVersion())
                    .normalizedScope(normalizedScope)
                    .stateDigest(observation.stateDigest())
                    .complete(true)
                    .revalidation(EvidenceRevalidation.builder()
                            .digestAlgorithm("SHA-256")
                            .observationType(EvidenceObservationType.SEARCH)
                            .toolSemantics(observation.toolSemantics())
                            .repositoryRelativePath(normalizedScope)
                            .normalizedQuery(observation.normalizedQuery())
                            .searchScope(observation.searchScope())
                            .engineVersion(observation.engineVersion())
                            .build())
                    .build());
            return result;
        } catch (IOException e) {
            return failure(e.getMessage(), startedAt);
        } catch (Exception e) {
            return failure(e.getMessage(), startedAt);
        }
    }

    private String text(ToolCall call, String key, String def) {
        if (call.getInput() == null || !call.getInput().has(key) || call.getInput().path(key).isNull()) {
            return def;
        }
        return call.getInput().path(key).asText(def);
    }

    private ToolResult failure(String message, long startedAt) {
        return ToolResult.failure("search_failed", message, elapsed(startedAt));
    }

    private long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }
}
