package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.api.dto.AgentApprovalDecisionRequest;
import cn.lunalhx.ai.api.dto.AgentAskRequest;
import cn.lunalhx.ai.api.dto.AgentReplayStreamRequest;
import cn.lunalhx.ai.api.dto.AgentUserInputRequest;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.UserInputAction;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRequestMapper {

    private final AgentRuntimeProperties agentRuntimeProperties;
    private final Validator validator;

    public record Problem(String code, String message) {}

    public record Result<T>(T value, Problem problem) {
        public boolean valid() {
            return problem == null;
        }

        public static <T> Result<T> success(T value) {
            return new Result<>(value, null);
        }

        public static <T> Result<T> failure(String code, String message) {
            return new Result<>(null, new Problem(code, message));
        }
    }

    public record ApprovalCommand(ApprovalDecision decision, String reason) {}

    public record UserInputCommand(UserInputAction action, String message) {}

    public Result<AgentQuestion> mapAsk(AgentAskRequest request) {
        if (request == null) {
            return Result.failure("invalid_request", "请求体不能为空");
        }
        if (!Boolean.TRUE.equals(agentRuntimeProperties.getEnabled())) {
            return Result.failure("agent_disabled", "Agent 功能未启用");
        }
        Set<ConstraintViolation<AgentAskRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .distinct()
                    .collect(Collectors.joining("; "));
            return Result.failure("invalid_request", message);
        }
        if (StringUtils.isBlank(request.getQuestion()) && StringUtils.isBlank(request.getMessage())) {
            return Result.failure("invalid_request", "question 不能为空");
        }
        String requestId = UUID.randomUUID().toString();
        AgentQuestion question = AgentQuestion.builder()
                .requestId(requestId)
                .conversationId(StringUtils.trimToNull(request.getConversationId()))
                .question(StringUtils.defaultIfBlank(StringUtils.trim(request.getQuestion()), StringUtils.trim(request.getMessage())))
                .workspace(request.getWorkspace())
                .maxSteps(request.getMaxSteps())
                .maxSegments(request.getMaxSegments())
                .includeTrace(request.getIncludeTrace())
                .build();
        return Result.success(question);
    }

    public Result<ApprovalCommand> mapApproval(AgentApprovalDecisionRequest request) {
        if (request == null) {
            return Result.failure("invalid_request", "请求体不能为空");
        }
        if (!Boolean.TRUE.equals(agentRuntimeProperties.getEnabled())) {
            return Result.failure("agent_disabled", "Agent 功能未启用");
        }
        Set<ConstraintViolation<AgentApprovalDecisionRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .distinct()
                    .collect(Collectors.joining("; "));
            return Result.failure("invalid_request", message);
        }
        ApprovalDecision decision = parseApprovalDecision(request.getDecision());
        if (decision == null) {
            return Result.failure("invalid_request", "decision 只能是 APPROVE 或 REJECT");
        }
        return Result.success(new ApprovalCommand(decision, request.getReason()));
    }

    public Result<String> mapRunId(String runId) {
        if (StringUtils.isBlank(runId)) {
            return Result.failure("invalid_request", "runId 不能为空");
        }
        return Result.success(runId);
    }

    public Result<UserInputCommand> mapUserInput(String runId, AgentUserInputRequest request) {
        if (StringUtils.isBlank(runId)) {
            return Result.failure("invalid_request", "runId 不能为空");
        }
        if (request == null) {
            return Result.failure("invalid_request", "请求体不能为空");
        }
        if (!Boolean.TRUE.equals(agentRuntimeProperties.getEnabled())) {
            return Result.failure("agent_disabled", "Agent 功能未启用");
        }
        Set<ConstraintViolation<AgentUserInputRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .distinct()
                    .collect(Collectors.joining("; "));
            return Result.failure("invalid_request", message);
        }
        UserInputAction action = parseUserInputAction(request.getAction());
        if (action == null) {
            return Result.failure("invalid_request", "action 只能是 CONTINUE 或 ABORT");
        }
        if (action == UserInputAction.CONTINUE && StringUtils.isBlank(request.getMessage())) {
            return Result.failure("invalid_request", "CONTINUE 必须提供非空 message");
        }
        return Result.success(new UserInputCommand(action, request.getMessage()));
    }

    public boolean resolveIncludeChildren(Boolean queryValue, AgentReplayStreamRequest body) {
        if (queryValue != null) {
            return queryValue;
        }
        if (body != null && body.getIncludeChildren() != null) {
            return body.getIncludeChildren();
        }
        return true;
    }

    private ApprovalDecision parseApprovalDecision(String value) {
        try {
            return ApprovalDecision.valueOf(StringUtils.upperCase(StringUtils.trim(value)));
        } catch (Exception e) {
            return null;
        }
    }

    private UserInputAction parseUserInputAction(String value) {
        try {
            return UserInputAction.valueOf(StringUtils.upperCase(StringUtils.trim(value)));
        } catch (Exception e) {
            return null;
        }
    }
}
