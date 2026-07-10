package cn.lunalhx.ai.infrastructure.gateway;

import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelGatewayException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;

public final class SpringAiErrorMapper {

    private static final Logger log = LoggerFactory.getLogger(SpringAiErrorMapper.class);

    private SpringAiErrorMapper() {
    }

    public static ModelGatewayException toException(Throwable ex, String provider, String model) {
        if (ex instanceof ModelGatewayException mge) {
            return mge;
        }
        if (ex instanceof HttpClientErrorException httpEx) {
            return mapHttpError(httpEx.getStatusCode().value(),
                    httpEx.getResponseBodyAsString(),
                    httpEx.getResponseHeaders() != null
                            ? httpEx.getResponseHeaders().getFirst("Retry-After") : null,
                    provider, model);
        }
        if (ex instanceof HttpServerErrorException httpEx) {
            return mapHttpError(httpEx.getStatusCode().value(),
                    httpEx.getResponseBodyAsString(),
                    httpEx.getResponseHeaders() != null
                            ? httpEx.getResponseHeaders().getFirst("Retry-After") : null,
                    provider, model);
        }
        if (ex instanceof ResourceAccessException || ex instanceof IOException) {
            return new ModelGatewayException(ModelErrorCode.PROVIDER_UNAVAILABLE,
                    "模型服务网络异常", true, null, ex);
        }
        if (ex instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return new ModelGatewayException(ModelErrorCode.MODEL_ERROR,
                    "模型调用线程被中断", false, null, ex);
        }

        String message = ex.getMessage();
        if (StringUtils.containsIgnoreCase(message, "401") || StringUtils.containsIgnoreCase(message, "unauthorized")) {
            return new ModelGatewayException(ModelErrorCode.AUTHENTICATION_FAILED,
                    StringUtils.abbreviate(message, 300), false, 401, null);
        }
        if (StringUtils.containsIgnoreCase(message, "402") || StringUtils.containsIgnoreCase(message, "insufficient")) {
            return new ModelGatewayException(ModelErrorCode.INSUFFICIENT_BALANCE,
                    StringUtils.abbreviate(message, 300), false, 402, null);
        }
        if (StringUtils.containsIgnoreCase(message, "429")) {
            return new ModelGatewayException(ModelErrorCode.RATE_LIMITED,
                    StringUtils.abbreviate(message, 300), true, 429, null);
        }
        if (isContextOverflowMessage(message)) {
            return new ModelGatewayException(ModelErrorCode.CONTEXT_OVERFLOW,
                    StringUtils.abbreviate(message, 300), false, null, null);
        }

        return new ModelGatewayException(ModelErrorCode.MODEL_ERROR,
                ModelErrorCode.MODEL_ERROR.defaultMessage(), false, null, ex);
    }

    private static ModelGatewayException mapHttpError(int statusCode, String responseBody,
                                                       String retryAfterHeader,
                                                       String provider, String model) {
        ModelErrorCode errorCode;
        boolean retryable = false;
        String providerMessage = extractErrorMessage(responseBody);

        if (statusCode == 401) {
            errorCode = ModelErrorCode.AUTHENTICATION_FAILED;
        } else if (statusCode == 402) {
            errorCode = ModelErrorCode.INSUFFICIENT_BALANCE;
        } else if (statusCode == 429) {
            errorCode = ModelErrorCode.RATE_LIMITED;
            retryable = true;
        } else if (statusCode == 400) {
            errorCode = isContextOverflowMessage(providerMessage)
                    ? ModelErrorCode.CONTEXT_OVERFLOW
                    : ModelErrorCode.BAD_REQUEST;
        } else if (statusCode == 422) {
            errorCode = isContextOverflowMessage(providerMessage)
                    ? ModelErrorCode.CONTEXT_OVERFLOW
                    : ModelErrorCode.INVALID_PARAMETER;
        } else if (statusCode == 503 || statusCode == 529) {
            errorCode = ModelErrorCode.PROVIDER_OVERLOADED;
            retryable = true;
        } else if (statusCode >= 500) {
            errorCode = ModelErrorCode.PROVIDER_UNAVAILABLE;
            retryable = true;
        } else {
            errorCode = ModelErrorCode.MODEL_ERROR;
        }

        String message = StringUtils.defaultIfBlank(providerMessage, errorCode.defaultMessage());
        Long retryAfterMs = parseRetryAfter(retryAfterHeader);
        log.warn("{} API returned status {}, model={}, errorCode={}, message={}",
                StringUtils.capitalize(provider), statusCode, model, errorCode.code(), message);

        return new ModelGatewayException(errorCode, message, retryable, statusCode, retryAfterMs, model, null);
    }

    private static boolean isContextOverflowMessage(String message) {
        if (message == null) return false;
        String normalized = StringUtils.lowerCase(message);
        return StringUtils.contains(normalized, "context length")
                || StringUtils.contains(normalized, "prompt too long")
                || StringUtils.contains(normalized, "prompt_too_long")
                || StringUtils.contains(normalized, "tokens exceed")
                || StringUtils.contains(normalized, "context_length_exceeded")
                || StringUtils.contains(normalized, "too many tokens");
    }

    private static String extractErrorMessage(String responseBody) {
        if (StringUtils.isBlank(responseBody)) return null;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(responseBody);
            com.fasterxml.jackson.databind.JsonNode errorNode = root.path("error");
            String message = null;
            if (!errorNode.isMissingNode()) {
                message = errorNode.path("message").asText(null);
            }
            return StringUtils.abbreviate(message, 300);
        } catch (Exception ignored) {
            return StringUtils.abbreviate(responseBody, 300);
        }
    }

    private static Long parseRetryAfter(String value) {
        if (StringUtils.isBlank(value)) return null;
        try {
            return Math.max(0L, Long.parseLong(value.trim()) * 1000L);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
