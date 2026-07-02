package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelGatewayException;
import org.apache.commons.lang3.StringUtils;

import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

final class ModelCallFailureClassifier {

    enum Category {
        BUDGET_EXCEEDED,
        TIMEOUT,
        CONTEXT_OVERFLOW,
        GATEWAY_ERROR,
        UNKNOWN
    }

    Category classify(Throwable throwable) {
        if (hasErrorCode(throwable, ModelErrorCode.BUDGET_EXCEEDED)) {
            return Category.BUDGET_EXCEEDED;
        }
        if (hasErrorCode(throwable, ModelErrorCode.MODEL_CALL_TIMEOUT) || isTimeoutException(throwable)) {
            return Category.TIMEOUT;
        }
        if (isContextOverflow(throwable)) {
            return Category.CONTEXT_OVERFLOW;
        }
        if (modelGatewayException(throwable) != null) {
            return Category.GATEWAY_ERROR;
        }
        return Category.UNKNOWN;
    }

    boolean hasErrorCode(Throwable throwable, ModelErrorCode errorCode) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ModelGatewayException exception
                    && exception.getErrorCode() == errorCode) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    boolean isTimeoutException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof HttpTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
            if (current instanceof IllegalStateException
                    && current.getMessage() != null
                    && current.getMessage().contains("Timeout on blocking read")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    boolean isContextOverflow(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ModelGatewayException exception
                    && exception.getErrorCode() == ModelErrorCode.CONTEXT_OVERFLOW) {
                return true;
            }
            String message = StringUtils.lowerCase(current.getMessage());
            if (StringUtils.contains(message, "prompt_too_long")
                    || StringUtils.contains(message, "context_length_exceeded")
                    || StringUtils.contains(message, "too many tokens")
                    || StringUtils.contains(message, "context length")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    ModelGatewayException modelGatewayException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ModelGatewayException exception) {
                return exception;
            }
            current = current.getCause();
        }
        return null;
    }

    String attemptedModel(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ModelGatewayException exception
                    && StringUtils.isNotBlank(exception.getModel())) {
                return exception.getModel();
            }
            current = current.getCause();
        }
        return null;
    }
}
