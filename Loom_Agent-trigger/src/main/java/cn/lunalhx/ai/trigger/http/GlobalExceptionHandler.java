package cn.lunalhx.ai.trigger.http;

import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public Response<Void> handleBadRequest(Exception e) {
        return Response.<Void>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info(e.getMessage())
                .build();
    }

    @ExceptionHandler(Exception.class)
    public Response<Void> handleException(Exception e) {
        log.error("Unhandled request error", e);
        return Response.<Void>builder()
                .code(ResponseCode.UN_ERROR.getCode())
                .info(ResponseCode.UN_ERROR.getInfo())
                .build();
    }

}
