package cn.lunalhx.ai.trigger.http;

import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.domain.common.CommonErrorCode;
import cn.lunalhx.ai.types.error.ApiError;
import cn.lunalhx.ai.types.error.ApplicationException;
import cn.lunalhx.ai.types.error.ErrorCategory;
import cn.lunalhx.ai.types.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<Response<Void>> handleApplicationException(ApplicationException e) {
        ApiError apiError = e.apiError();
        HttpStatus httpStatus = toHttpStatus(apiError.category());
        if (apiError.category() == ErrorCategory.INTERNAL) {
            log.error("Application error (internal): code={}, message={}", apiError.code(), apiError.message(), e);
        } else {
            log.warn("Application error: code={}, message={}", apiError.code(), apiError.message());
        }
        return ResponseEntity.status(httpStatus).body(Response.failure(apiError));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Response<Void>> handleValidation(Exception e) {
        log.warn("Request validation failed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Response.failure(CommonErrorCode.INVALID_PARAMETER, getValidationMessage(e)));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Response<Void>> handleResponseStatus(ResponseStatusException e) {
        log.warn("Response status exception: status={}, message={}", e.getStatusCode(), e.getReason());
        ErrorCode errorCode = mapStatusToErrorCode(e.getStatusCode().value());
        return ResponseEntity.status(e.getStatusCode())
                .body(Response.failure(errorCode, e.getReason()));
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException e) {
        // 客户端已断开，无法写入响应
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Response<Void>> handleException(Exception e) {
        log.error("Unhandled request error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Response.failure(CommonErrorCode.UNKNOWN));
    }

    private HttpStatus toHttpStatus(ErrorCategory category) {
        return HttpStatus.valueOf(category.httpStatus());
    }

    private String getValidationMessage(Exception e) {
        if (e instanceof MethodArgumentNotValidException ex) {
            return ex.getBindingResult().getFieldErrors().stream()
                    .map(f -> f.getField() + ": " + f.getDefaultMessage())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("参数校验失败");
        }
        if (e instanceof HttpMessageNotReadableException) {
            return "请求体格式错误";
        }
        return e.getMessage();
    }

    private ErrorCode mapStatusToErrorCode(int httpStatus) {
        return switch (httpStatus) {
            case 400 -> CommonErrorCode.INVALID_PARAMETER;
            case 404 -> CommonErrorCode.INVALID_REQUEST;
            default -> CommonErrorCode.UNKNOWN;
        };
    }
}
