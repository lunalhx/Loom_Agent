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
import java.nio.file.Files;
import java.nio.file.Path;

/** loom-code {@code list_files}: list one complete directory level. */
@Component
public class ListFilesTool implements AgentTool {

    private final WorkspacePort workspacePort;
    private final ListFilesObservationService observationService;

    public ListFilesTool(WorkspacePort workspacePort) {
        this.workspacePort = workspacePort;
        this.observationService = new ListFilesObservationService();
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("list_files")
                .description("List files in the workspace.")
                .inputSchema("{" +
                        "\"type\":\"object\",\n" +
                        "\"properties\":{\"path\":{\"type\":\"string\",\"default\":\".\",\"description\":\"directory\"}}," +
                        "\"required\":[]," +
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
            String rawPath = call.getInput() != null && call.getInput().has("path")
                    ? call.getInput().path("path").asText(".") : ".";
            Path root = LoomToolSupport.root(workspacePort, call);
            Path dir = LoomToolSupport.resolve(workspacePort, call, rawPath);
            if (!Files.isDirectory(dir)) {
                return failure("path is not a directory", startedAt);
            }

            ListFilesObservationService.Observation observation =
                    observationService.observe(root, dir);
            String scope = observation.normalizedScope();
            ToolResult result = ToolResult.success(observation.render(), false, elapsed(startedAt));
            result.setEvidenceCandidate(ToolEvidenceCandidate.builder()
                    .evidenceKey("list_files|" + scope)
                    .normalizedScope(scope)
                    .stateDigest(observation.stateDigest())
                    .complete(true)
                    .revalidation(EvidenceRevalidation.builder()
                            .digestAlgorithm("SHA-256")
                            .observationType(EvidenceObservationType.LIST_FILES)
                            .toolSemantics(ListFilesObservationService.TOOL_SEMANTICS)
                            .repositoryRelativePath(scope)
                            .build())
                    .build());
            return result;
        } catch (IOException e) {
            return failure(e.getMessage(), startedAt);
        } catch (Exception e) {
            return failure(e.getMessage(), startedAt);
        }
    }

    private ToolResult failure(String message, long startedAt) {
        return ToolResult.failure("list_files_failed", message, elapsed(startedAt));
    }

    private long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }
}
