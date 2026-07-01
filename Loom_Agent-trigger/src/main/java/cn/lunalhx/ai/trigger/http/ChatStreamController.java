package cn.lunalhx.ai.trigger.http;

import cn.lunalhx.ai.api.dto.ChatStreamRequest;
import cn.lunalhx.ai.api.dto.ResponseFormat;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.StreamEvent;
import cn.lunalhx.ai.domain.conversation.service.ChatStreamService;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.OutputFormat;
import cn.lunalhx.ai.domain.model.valobj.StreamEventType;
import cn.lunalhx.ai.trigger.http.chat.ChatSseResponder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/chat")
public class ChatStreamController {

    private final ChatStreamService chatStreamService;
    private final Validator validator;
    private final StreamRequestLimiter streamRequestLimiter;
    private final ChatSseResponder chatSseResponder;

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody(required = false) ChatStreamRequest request,
                              HttpServletRequest httpRequest) {
        StreamEvent validationError = validateRequest(request);
        if (validationError != null) {
            return chatSseResponder.completedError(validationError);
        }

        String clientKey = streamRequestLimiter.resolveClientKey(httpRequest);
        StreamRequestLimiter.Lease lease = streamRequestLimiter.tryAcquire(clientKey, "chat-stream");
        if (!lease.isAllowed()) {
            StreamEvent rejectEvent = StreamEvent.builder()
                    .type(StreamEventType.ERROR)
                    .requestId(UUID.randomUUID().toString())
                    .code(lease.rejectCode())
                    .message(lease.rejectMessage())
                    .build();
            return chatSseResponder.completedError(rejectEvent);
        }

        String requestId = UUID.randomUUID().toString();
        return chatSseResponder.stream(request, requestId,
                () -> chatStreamService.stream(toPrompt(request, requestId)),
                lease::release);
    }

    private StreamEvent validateRequest(ChatStreamRequest request) {
        String requestId = UUID.randomUUID().toString();
        if (request == null) {
            return StreamEvent.builder()
                    .type(StreamEventType.ERROR)
                    .requestId(requestId)
                    .code(ModelErrorCode.INVALID_REQUEST.code())
                    .message("请求体不能为空")
                    .build();
        }
        Set<ConstraintViolation<ChatStreamRequest>> violations = validator.validate(request);
        if (violations.isEmpty()) {
            return null;
        }
        String message = violations.stream()
                .map(ConstraintViolation::getMessage)
                .distinct()
                .collect(Collectors.joining("; "));
        return StreamEvent.builder()
                .type(StreamEventType.ERROR)
                .requestId(requestId)
                .conversationId(request.getConversationId())
                .model(request.getModel())
                .code(ModelErrorCode.INVALID_REQUEST.code())
                .message(message)
                .build();
    }

    private ChatPrompt toPrompt(ChatStreamRequest request, String requestId) {
        ResponseFormat responseFormat = request.getResponseFormat() == null ? ResponseFormat.TEXT : request.getResponseFormat();
        return ChatPrompt.builder()
                .requestId(requestId)
                .conversationId(request.getConversationId())
                .message(StringUtils.trim(request.getMessage()))
                .systemPrompt(request.getSystemPrompt())
                .model(request.getModel())
                .temperature(request.getTemperature())
                .maxTokens(request.getMaxTokens())
                .outputFormat(OutputFormat.valueOf(responseFormat.name()))
                .build();
    }
}
