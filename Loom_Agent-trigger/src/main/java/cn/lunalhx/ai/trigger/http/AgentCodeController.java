package cn.lunalhx.ai.trigger.http;

import cn.lunalhx.ai.api.dto.AgentApprovalDecisionRequest;
import cn.lunalhx.ai.api.dto.AgentApprovalResponse;
import cn.lunalhx.ai.api.dto.AgentAskRequest;
import cn.lunalhx.ai.api.dto.AgentReplayResponse;
import cn.lunalhx.ai.api.dto.AgentReplayStreamRequest;
import cn.lunalhx.ai.api.dto.AgentTraceTimelineResponse;
import cn.lunalhx.ai.api.dto.AgentUserInputRequest;
import cn.lunalhx.ai.api.dto.BackgroundTaskDetailResponse;
import cn.lunalhx.ai.api.dto.BackgroundTaskResponse;
import cn.lunalhx.ai.api.dto.ConversationDeletionResponse;
import cn.lunalhx.ai.api.dto.ConversationSummaryResponse;
import cn.lunalhx.ai.api.dto.SkillQueryRequest;
import cn.lunalhx.ai.api.dto.SkillQueryResponse;
import cn.lunalhx.ai.api.dto.UndoExecuteRequest;
import cn.lunalhx.ai.api.dto.UndoExecuteResponse;
import cn.lunalhx.ai.api.dto.UndoStatusResponse;
import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.SkillRepository;
import cn.lunalhx.ai.domain.tool.adapter.port.BackgroundShellTaskRepository;
import cn.lunalhx.ai.domain.tool.model.BackgroundShellTask;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.SkillCatalog;
import cn.lunalhx.ai.domain.agent.model.entity.SkillDescriptor;
import cn.lunalhx.ai.domain.agent.model.entity.SkillSource;
import cn.lunalhx.ai.domain.agent.model.valobj.SkillTrustState;
import cn.lunalhx.ai.domain.agent.service.AgentLoopService;
import cn.lunalhx.ai.domain.agent.service.AgentWorkspaceResolver;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentWorkspace;
import cn.lunalhx.ai.domain.agent.service.ConversationDeletionService;
import cn.lunalhx.ai.trigger.http.agent.AgentHttpQueryService;
import cn.lunalhx.ai.trigger.http.agent.AgentRequestMapper;
import cn.lunalhx.ai.trigger.http.agent.AgentSseResponder;
import cn.lunalhx.ai.trigger.http.agent.AgentUndoHttpService;
import cn.lunalhx.ai.types.enums.ResponseCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/agent/code")
public class AgentCodeController {

    private final AgentLoopService agentLoopService;
    private final AgentRequestMapper requestMapper;
    private final AgentHttpQueryService queryService;
    private final AgentSseResponder sseResponder;
    private final StreamRequestLimiter streamRequestLimiter;
    private final AgentUndoHttpService undoHttpService;
    private final SkillRepository skillRepository;
    private final AgentWorkspaceResolver workspaceResolver;
    private final AgentRuntimeProperties agentRuntimeProperties;
    private final ConversationDeletionService deletionService;
    private final AgentRunRepository runRepository;
    private final BackgroundShellTaskRepository taskRepository;

    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@RequestBody(required = false) AgentAskRequest request,
                           HttpServletRequest httpRequest) {
        AgentRequestMapper.Result<AgentQuestion> result = requestMapper.mapAsk(request);
        if (!result.valid()) {
            return sseResponder.completedAgentError(result.problem());
        }

        AgentQuestion question = result.value();
        if (question.getConversationId() != null && deletionService.isConversationDeleted(question.getConversationId())) {
            return sseResponder.completedAgentError(
                    new AgentRequestMapper.Problem(ResponseCode.CONVERSATION_DELETED.getCode(), "会话已删除"));
        }

        String clientKey = streamRequestLimiter.resolveClientKey(httpRequest);
        StreamRequestLimiter.Lease lease = streamRequestLimiter.tryAcquire(clientKey, "agent-ask");
        if (!lease.isAllowed()) {
            return sseResponder.completedAgentError(
                    new AgentRequestMapper.Problem(lease.rejectCode(), lease.rejectMessage()));
        }

        AgentSseResponder.StreamProfile profile = Boolean.TRUE.equals(request.getIncludeTrace())
                ? AgentSseResponder.StreamProfile.ALL_EVENTS
                : AgentSseResponder.StreamProfile.PUBLIC_ASK;

        return sseResponder.streamAgentEvents(
                "ask",
                question.getRequestId(),
                () -> agentLoopService.ask(question),
                profile,
                lease::release
        );
    }

    @PostMapping(value = "/approvals/{approvalId}/decide/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter decide(@PathVariable String approvalId,
                             @RequestBody(required = false) AgentApprovalDecisionRequest request) {
        AgentRequestMapper.Result<AgentRequestMapper.ApprovalCommand> result = requestMapper.mapApproval(request);
        if (!result.valid()) {
            return sseResponder.completedAgentError(result.problem());
        }

        return sseResponder.streamAgentEvents(
                "decide",
                UUID.randomUUID().toString(),
                () -> agentLoopService.resume(approvalId, result.value().decision(), result.value().reason()),
                AgentSseResponder.StreamProfile.ALL_EVENTS
        );
    }

    @GetMapping("/approvals/{approvalId}")
    public Response<AgentApprovalResponse> approval(@PathVariable String approvalId) {
        return queryService.approval(approvalId);
    }

    @PostMapping(value = "/runs/{runId}/resume/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resumeRun(@PathVariable String runId) {
        AgentRequestMapper.Result<String> result = requestMapper.mapRunId(runId);
        if (!result.valid()) {
            return sseResponder.completedAgentError(result.problem());
        }

        return sseResponder.streamAgentEvents(
                "resumeRun",
                UUID.randomUUID().toString(),
                () -> agentLoopService.resumeRun(runId),
                AgentSseResponder.StreamProfile.WITHOUT_CHECKPOINT
        );
    }

    @PostMapping(value = "/runs/{runId}/input/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resumeWithUserInput(@PathVariable String runId,
                                          @RequestBody(required = false) AgentUserInputRequest request) {
        AgentRequestMapper.Result<AgentRequestMapper.UserInputCommand> result = requestMapper.mapUserInput(runId, request);
        if (!result.valid()) {
            return sseResponder.completedAgentError(result.problem());
        }

        return sseResponder.streamAgentEvents(
                "userInput",
                UUID.randomUUID().toString(),
                () -> agentLoopService.resumeWithUserInput(runId, result.value().action(), result.value().message()),
                AgentSseResponder.StreamProfile.ALL_EVENTS
        );
    }

    @PostMapping("/runs/{runId}/cancel")
    public Response<Boolean> cancelRun(@PathVariable String runId) {
        AgentRequestMapper.Result<String> result = requestMapper.mapRunId(runId);
        if (!result.valid()) {
            return Response.<Boolean>builder()
                    .code(result.problem().code())
                    .info(result.problem().message())
                    .data(false)
                    .build();
        }
        boolean cancelled = agentLoopService.cancelRun(result.value());
        return Response.<Boolean>builder()
                .code("0000")
                .info(cancelled ? "cancelled" : "run_not_active")
                .data(cancelled)
                .build();
    }

    @GetMapping("/runs/{runId}/trace")
    public Response<AgentTraceTimelineResponse> trace(@PathVariable String runId) {
        return queryService.trace(runId);
    }

    @GetMapping("/runs/{runId}/replay")
    public Response<AgentReplayResponse> replay(@PathVariable String runId,
                                                @RequestParam(required = false) Boolean includeChildren) {
        return queryService.replay(runId, requestMapper.resolveIncludeChildren(includeChildren, null));
    }

    @PostMapping(value = "/runs/{runId}/replay/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter replayStream(@PathVariable String runId,
                                   @RequestParam(required = false) Boolean includeChildren,
                                   @RequestBody(required = false) AgentReplayStreamRequest request) {
        AgentRequestMapper.Result<String> result = requestMapper.mapRunId(runId);
        if (!result.valid()) {
            return sseResponder.completedReplayError(result.problem());
        }

        boolean resolvedIncludeChildren = requestMapper.resolveIncludeChildren(includeChildren, request);

        return sseResponder.streamReplay(
                runId,
                resolvedIncludeChildren,
                () -> queryService.replayTimeline(runId, resolvedIncludeChildren)
        );
    }

    @GetMapping("/runs/{runId}/undo")
    public Response<UndoStatusResponse> undoStatus(@PathVariable String runId) {
        return undoHttpService.query(runId);
    }

    @PostMapping("/runs/{runId}/undo")
    public Response<UndoExecuteResponse> undoExecute(@PathVariable String runId,
                                                       @RequestBody UndoExecuteRequest request) {
        return undoHttpService.execute(runId, request);
    }

    @PostMapping("/skills/query")
    public Response<List<SkillQueryResponse>> querySkills(@RequestBody(required = false) SkillQueryRequest request) {
        AgentRuntimeProperties.SkillProperties skillProps = agentRuntimeProperties.getSkills();
        if (skillProps != null && Boolean.FALSE.equals(skillProps.getEnabled())) {
            return Response.<List<SkillQueryResponse>>builder()
                    .code("0000")
                    .info("ok")
                    .data(List.of())
                    .build();
        }

        String workspace = request == null ? null : request.getWorkspace();
        AgentWorkspace resolved = workspaceResolver.resolve(workspace);
        SkillCatalog catalog = skillRepository.discover(resolved.getRoot());

        List<SkillQueryResponse> items = new ArrayList<>();
        for (SkillDescriptor skill : catalog.skills()) {
            SkillTrustState trustState = skill.source() == SkillSource.USER
                    ? SkillTrustState.TRUSTED
                    : SkillTrustState.APPROVAL_REQUIRED;
            items.add(SkillQueryResponse.builder()
                    .name(skill.name())
                    .description(skill.description())
                    .source(skill.source().name().toLowerCase())
                    .compatibility(skill.compatibility())
                    .trustState(trustState.name().toLowerCase())
                    .diagnostics(catalog.diagnostics())
                    .build());
        }
        return Response.<List<SkillQueryResponse>>builder()
                .code("0000")
                .info("ok")
                .data(items)
                .build();
    }

    @GetMapping("/conversations")
    public Response<List<ConversationSummaryResponse>> listConversations() {
        List<ConversationSummaryResponse> list = runRepository.listConversationSummaries().stream()
                .map(s -> ConversationSummaryResponse.builder()
                        .conversationId(s.getConversationId())
                        .title(s.getTitle())
                        .runCount(s.getRunCount())
                        .workspace(s.getWorkspace())
                        .build())
                .toList();
        return Response.<List<ConversationSummaryResponse>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info("ok")
                .data(list)
                .build();
    }

    @DeleteMapping("/conversations/{conversationId}")
    public Response<ConversationDeletionResponse> deleteConversation(@PathVariable String conversationId) {
        ConversationDeletionService.DeletionRequestResult result = deletionService.requestDeletion(conversationId);

        if (result.invalid()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, result.lastError());
        }
        if (result.notFound()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "conversation not found");
        }

        HttpStatus httpStatus = result.isAlreadyCompleted() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return Response.<ConversationDeletionResponse>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(result.isAlreadyCompleted() ? "already deleted" : "deletion requested")
                .data(toDeletionResponse(result))
                .build();
    }

    @GetMapping("/conversations/{conversationId}/deletion")
    public Response<ConversationDeletionResponse> deletionStatus(@PathVariable String conversationId) {
        return deletionService.getDeletionStatus(conversationId)
                .map(result -> Response.<ConversationDeletionResponse>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info("ok")
                        .data(toDeletionResponse(result))
                        .build())
                .orElseGet(() -> Response.<ConversationDeletionResponse>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info("not found")
                        .build());
    }

    @GetMapping("/runs/{runId}/background-tasks")
    public Response<List<BackgroundTaskResponse>> listBackgroundTasks(@PathVariable String runId) {
        List<BackgroundShellTask> tasks = taskRepository.findByRunId(runId);
        List<BackgroundTaskResponse> list = tasks.stream()
                .map(t -> BackgroundTaskResponse.builder()
                        .taskId(t.getTaskId())
                        .runId(t.getRunId())
                        .conversationId(t.getConversationId())
                        .workspace(t.getWorkspace())
                        .command(t.getCommand())
                        .cwd(t.getCwd())
                        .launchMode(t.getLaunchMode() == null ? null : t.getLaunchMode().name())
                        .timeoutMs(t.getTimeoutMs())
                        .pid(t.getPid())
                        .status(t.getStatus() == null ? null : t.getStatus().name())
                        .exitCode(t.getExitCode())
                        .errorCode(t.getErrorCode())
                        .errorMessage(t.getErrorMessage())
                        .stdoutBytes(t.getStdoutBytes())
                        .stderrBytes(t.getStderrBytes())
                        .startedAt(t.getStartedAt() == null ? null : t.getStartedAt().toString())
                        .completedAt(t.getCompletedAt() == null ? null : t.getCompletedAt().toString())
                        .completionNotified(t.isCompletionNotified())
                        .build())
                .toList();
        return Response.<List<BackgroundTaskResponse>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info("ok")
                .data(list)
                .build();
    }

    @GetMapping("/runs/{runId}/background-tasks/{taskId}")
    public Response<BackgroundTaskDetailResponse> getBackgroundTask(@PathVariable String runId,
                                                                      @PathVariable String taskId,
                                                                      @RequestParam(defaultValue = "0") long stdoutOffset,
                                                                      @RequestParam(defaultValue = "0") long stderrOffset,
                                                                      @RequestParam(defaultValue = "8192") int limitBytes) {
        BackgroundShellTask task = taskRepository.find(taskId).orElse(null);
        if (task == null || !runId.equals(task.getRunId())) {
            return Response.<BackgroundTaskDetailResponse>builder()
                    .code("BACKGROUND_TASK_NOT_FOUND")
                    .info("任务未找到")
                    .build();
        }

        String stdoutChunk = readChunk(task.getStdoutFile(), stdoutOffset, limitBytes);
        String stderrChunk = readChunk(task.getStderrFile(), stderrOffset, limitBytes);
        long stdoutEnd = stdoutOffset + (stdoutChunk != null ? stdoutChunk.length() : 0);
        long stderrEnd = stderrOffset + (stderrChunk != null ? stderrChunk.length() : 0);

        return Response.<BackgroundTaskDetailResponse>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info("ok")
                .data(BackgroundTaskDetailResponse.builder()
                        .taskId(task.getTaskId())
                        .runId(task.getRunId())
                        .status(task.getStatus() == null ? null : task.getStatus().name())
                        .exitCode(task.getExitCode())
                        .errorCode(task.getErrorCode())
                        .errorMessage(task.getErrorMessage())
                        .stdoutChunk(stdoutChunk)
                        .stderrChunk(stderrChunk)
                        .stdoutOffset(stdoutEnd)
                        .stderrOffset(stderrEnd)
                        .stdoutEof(stdoutEnd >= task.getStdoutBytes())
                        .stderrEof(stderrEnd >= task.getStderrBytes())
                        .stdoutBytes(task.getStdoutBytes())
                        .stderrBytes(task.getStderrBytes())
                        .command(task.getCommand())
                        .cwd(task.getCwd())
                        .launchMode(task.getLaunchMode() == null ? null : task.getLaunchMode().name())
                        .timeoutMs(task.getTimeoutMs())
                        .build())
                .build();
    }

    @PostMapping("/runs/{runId}/background-tasks/{taskId}/cancel")
    public Response<BackgroundTaskResponse> cancelBackgroundTask(@PathVariable String runId,
                                                                   @PathVariable String taskId) {
        BackgroundShellTask task = taskRepository.find(taskId).orElse(null);
        if (task == null || !runId.equals(task.getRunId())) {
            return Response.<BackgroundTaskResponse>builder()
                    .code("BACKGROUND_TASK_NOT_FOUND")
                    .info("任务未找到")
                    .build();
        }
        return Response.<BackgroundTaskResponse>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info("ok")
                .data(BackgroundTaskResponse.builder()
                        .taskId(task.getTaskId())
                        .runId(task.getRunId())
                        .status(task.getStatus() == null ? null : task.getStatus().name())
                        .build())
                .build();
    }

    private String readChunk(String filePath, long offset, int limitBytes) {
        if (filePath == null) {
            return null;
        }
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            int start = (int) Math.min(offset, bytes.length);
            int end = Math.min(start + limitBytes, bytes.length);
            if (start >= end) {
                return "";
            }
            return new String(bytes, start, end - start, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(读取错误: " + e.getMessage() + ")";
        }
    }

    private static ConversationDeletionResponse toDeletionResponse(
            ConversationDeletionService.DeletionRequestResult result) {
        return ConversationDeletionResponse.builder()
                .conversationId(result.conversationId())
                .status(result.status())
                .requestedAt(result.requestedAt())
                .completedAt(result.completedAt())
                .retryCount(result.retryCount())
                .lastError(result.lastError())
                .build();
    }
}
