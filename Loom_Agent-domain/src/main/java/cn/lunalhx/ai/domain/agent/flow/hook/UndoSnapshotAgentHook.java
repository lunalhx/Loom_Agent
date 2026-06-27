package cn.lunalhx.ai.domain.agent.flow.hook;

import cn.lunalhx.ai.domain.agent.adapter.port.UndoSnapshotRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentUndoSnapshot;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.UndoSessionCoordinator;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class UndoSnapshotAgentHook implements AgentHook {

    private static final Logger log = LoggerFactory.getLogger(UndoSnapshotAgentHook.class);

    private static final Set<String> FILE_TOOL_NAMES = Set.of(
            "write_file", "replace_in_file", "delete_files", "writeFile", "replaceInFile", "deleteFiles");

    private static final Set<String> IRREVERSIBLE_GIT_OPS = Set.of(
            "commit", "checkout", "switch", "rebase", "reset", "push");

    private final UndoSessionCoordinator coordinator;
    private final UndoSnapshotRepository snapshotRepository;
    private final AgentRuntimeProperties.UndoProperties config;

    public UndoSnapshotAgentHook(UndoSessionCoordinator coordinator,
                                  UndoSnapshotRepository snapshotRepository,
                                  AgentRuntimeProperties.UndoProperties config) {
        this.coordinator = coordinator;
        this.snapshotRepository = snapshotRepository;
        this.config = config;
    }

    @Override
    public AgentHookResult onEvent(AgentHookEvent event, AgentHookContext hookContext) {
        if (!config.isEnabled()) {
            return AgentHookResult.proceed();
        }

        AgentContext context = hookContext.getAgentContext();
        if (context == null || StringUtils.isBlank(context.getRunId())) {
            return AgentHookResult.proceed();
        }
        if (StringUtils.isNotBlank(context.getParentRunId())) {
            return AgentHookResult.proceed();
        }

        if (event == AgentHookEvent.AFTER_TOOL) {
            handleAfterTool(context, hookContext);
        } else if (event == AgentHookEvent.AFTER_STOP) {
            coordinator.finalizeSnapshot(context);
        }

        return AgentHookResult.proceed();
    }

    private void handleAfterTool(AgentContext context, AgentHookContext hookContext) {
        AgentUndoSnapshot snapshot = snapshotRepository.findByRunId(context.getRunId()).orElse(null);
        if (snapshot == null) {
            return;
        }

        ToolCall toolCall = hookContext.getToolCall();
        if (toolCall == null) {
            return;
        }

        String toolName = toolCall.getName();
        if (toolName == null) {
            return;
        }

        if (FILE_TOOL_NAMES.contains(toolName)) {
            return;
        }

        if (isGitTool(toolName)) {
            checkGitOperation(context, toolCall);
            return;
        }

        if ("run_shell".equals(toolName)) {
            checkShellForGitOp(context, toolCall);
        }
    }

    private boolean isGitTool(String toolName) {
        return "git".equalsIgnoreCase(toolName) || "git_op".equalsIgnoreCase(toolName);
    }

    private void checkGitOperation(AgentContext context, ToolCall toolCall) {
        JsonNode input = toolCall.getInput();
        if (input == null) {
            return;
        }

        JsonNode opNode = input.get("operation");
        String operation = opNode != null ? opNode.asText().toLowerCase(Locale.ROOT) : null;
        if (operation != null && IRREVERSIBLE_GIT_OPS.contains(operation)) {
            coordinator.markUnavailable(context.getRunId(),
                    "irreversible_git_op", "run used git " + operation);
        }
    }

    private void checkShellForGitOp(AgentContext context, ToolCall toolCall) {
        JsonNode input = toolCall.getInput();
        if (input == null) {
            return;
        }

        JsonNode cmdNode = input.get("command");
        String command = cmdNode != null ? cmdNode.asText().trim() : null;
        if (command == null) {
            return;
        }

        List<String> tokens = Arrays.asList(command.split("\\s+"));
        if (tokens.isEmpty() || !tokens.get(0).equals("git")) {
            return;
        }

        if (tokens.size() < 2) {
            return;
        }

        String operation = tokens.get(1).toLowerCase(Locale.ROOT);
        if (IRREVERSIBLE_GIT_OPS.contains(operation)) {
            coordinator.markUnavailable(context.getRunId(),
                    "irreversible_git_op", "run used git " + operation + " in shell");
        }
    }
}
