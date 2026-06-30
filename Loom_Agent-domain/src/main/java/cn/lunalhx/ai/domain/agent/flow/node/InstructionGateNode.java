package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

public class InstructionGateNode extends AbstractAgentNode {

    private static final String OVERRIDE_FILE = "AGENTS.override.md";
    private static final String DEFAULT_FILE = "AGENTS.md";
    private static final int MAX_FILES = 10;
    private static final int MAX_FILE_SIZE = 50_000;

    public InstructionGateNode() {
        super(AgentNodeNames.INSTRUCTION_GATE, List.of("decision", "resolvedWorkspace"));
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        AgentDecision decision = context.getDecision();
        if (decision == null || !"action".equals(decision.getType())) {
            return NodeResult.next(AgentNodeNames.APPROVAL_GATE, List.of());
        }

        if (!isWritableTool(decision.getTool())) {
            return NodeResult.next(AgentNodeNames.APPROVAL_GATE, List.of());
        }

        String instructions = loadInstructionsChain(context.getResolvedWorkspace(), context.getPathScope());
        if (instructions == null || instructions.isEmpty()) {
            return NodeResult.next(AgentNodeNames.APPROVAL_GATE, List.of());
        }

        String hash = sha256(instructions);
        if (!Objects.equals(hash, context.getInstructionsHash())) {
            context.setInstructionsHash(hash);
            context.setPromptRenderCacheKey(null);
            return NodeResult.next(AgentNodeNames.RENDER_PROMPT, List.of());
        }

        return NodeResult.next(AgentNodeNames.APPROVAL_GATE, List.of());
    }

    private boolean isWritableTool(String toolName) {
        return toolName != null && (toolName.contains("file") || toolName.contains("shell")
                || toolName.contains("git") || toolName.contains("write")
                || toolName.contains("replace") || toolName.contains("delete"));
    }

    String loadInstructionsChain(Path workspace, String pathScope) {
        if (workspace == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;

        Path override = workspace.resolve(OVERRIDE_FILE);
        if (Files.isRegularFile(override)) {
            String content = readFileSafe(override);
            if (content != null) {
                if (content.length() > MAX_FILE_SIZE) {
                    content = content.substring(0, MAX_FILE_SIZE);
                }
                sb.append("[工作区根指令 override]\n").append(content).append('\n');
                count++;
            }
        } else {
            Path defaultFile = workspace.resolve(DEFAULT_FILE);
            if (Files.isRegularFile(defaultFile)) {
                String content = readFileSafe(defaultFile);
                if (content != null) {
                    if (content.length() > MAX_FILE_SIZE) {
                        content = content.substring(0, MAX_FILE_SIZE);
                    }
                    sb.append("[工作区根指令]\n").append(content).append('\n');
                    count++;
                }
            }
        }

        if (pathScope != null && !pathScope.isBlank() && count < MAX_FILES) {
            Path scopePath = workspace.resolve(pathScope).normalize();
            if (scopePath.startsWith(workspace)) {
                Path current = workspace;
                for (Path component : workspace.relativize(scopePath)) {
                    current = current.resolve(component);
                    Path agentsMd = current.resolve(DEFAULT_FILE);
                    if (Files.isRegularFile(agentsMd) && !agentsMd.equals(workspace.resolve(DEFAULT_FILE))
                            && !agentsMd.equals(workspace.resolve(OVERRIDE_FILE))) {
                        String content = readFileSafe(agentsMd);
                        if (content != null) {
                            if (content.length() > MAX_FILE_SIZE) {
                                content = content.substring(0, MAX_FILE_SIZE);
                            }
                            sb.append("[路径指令 ").append(workspace.relativize(current)).append("]\n")
                                    .append(content).append('\n');
                            count++;
                            if (count >= MAX_FILES) break;
                        }
                    }
                }
            }
        }

        return sb.isEmpty() ? null : sb.toString();
    }

    private String readFileSafe(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
