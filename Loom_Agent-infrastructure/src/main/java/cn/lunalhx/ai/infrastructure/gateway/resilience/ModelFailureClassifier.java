package cn.lunalhx.ai.infrastructure.gateway.resilience;

import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelGatewayException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

public final class ModelFailureClassifier {

    Throwable unwrap(Throwable error) {
        if (error instanceof RuntimeException && error.getCause() != null
                && "reactor.core.Exceptions$ReactiveException".equals(error.getClass().getName())) {
            return error.getCause();
        }
        return error;
    }

    Throwable normalize(Throwable error, String model) {
        Throwable unwrapped = unwrap(error);
        if (unwrapped instanceof ModelGatewayException exception && StringUtils.isBlank(exception.getModel())) {
            exception.setModel(model);
        }
        return unwrapped;
    }

    boolean retryable(Throwable error) {
        Throwable unwrapped = unwrap(error);
        if (unwrapped instanceof ModelGatewayException exception) {
            if (isNonRetryable(exception)) {
                return false;
            }
            return exception.isRetryable();
        }
        return unwrapped instanceof TimeoutException
                || unwrapped instanceof HttpTimeoutException
                || unwrapped instanceof ConnectException
                || unwrapped instanceof IOException;
    }

    boolean overload(Throwable error) {
        return error instanceof ModelGatewayException exception
                && exception.getErrorCode() == ModelErrorCode.PROVIDER_OVERLOADED;
    }

    boolean insufficientSystemResource(String finishReason) {
        return "insufficient_system_resource".equalsIgnoreCase(StringUtils.trimToEmpty(finishReason));
    }

    String errorCode(Throwable error) {
        if (error instanceof ModelGatewayException exception && exception.getErrorCode() != null) {
            return exception.getErrorCode().code();
        }
        if (error instanceof CallNotPermittedException) {
            return "circuit_open";
        }
        return error == null ? "none" : error.getClass().getSimpleName();
    }

    ModelGatewayException overloaded(String model, String message) {
        return new ModelGatewayException(ModelErrorCode.PROVIDER_OVERLOADED, message, true, 503,
                null, model, null);
    }

    ModelGatewayException deadlineExceeded(String model) {
        return new ModelGatewayException(ModelErrorCode.MODEL_CALL_TIMEOUT,
                ModelErrorCode.MODEL_CALL_TIMEOUT.message(), false, null, null, model, null);
    }

    private boolean isNonRetryable(ModelGatewayException exception) {
        if (exception.getHttpStatus() != null
                && (exception.getHttpStatus() == 401
                || exception.getHttpStatus() == 402
                || exception.getHttpStatus() == 422)) {
            return true;
        }
        ModelErrorCode code = exception.getErrorCode();
        return code == ModelErrorCode.CONFIG_ERROR
                || code == ModelErrorCode.INVALID_REQUEST
                || code == ModelErrorCode.BAD_REQUEST
                || code == ModelErrorCode.INVALID_PARAMETER
                || code == ModelErrorCode.CONTEXT_OVERFLOW
                || code == ModelErrorCode.MODEL_CAPABILITY_MISMATCH
                || code == ModelErrorCode.MODEL_CALL_TIMEOUT
                || code == ModelErrorCode.AUTHENTICATION_FAILED
                || code == ModelErrorCode.INSUFFICIENT_BALANCE;
    }

}
