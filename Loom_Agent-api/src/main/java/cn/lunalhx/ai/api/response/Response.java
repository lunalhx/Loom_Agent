package cn.lunalhx.ai.api.response;

import cn.lunalhx.ai.types.enums.ResponseCode;
import cn.lunalhx.ai.types.error.ApiError;
import cn.lunalhx.ai.types.error.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Response<T> implements Serializable {

    private static final long serialVersionUID = 7000723935764546321L;

    private String code;
    private String info;
    private T data;

    public static <T> Response<T> success(T data) {
        return Response.<T>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }

    public static <T> Response<T> success(T data, String info) {
        return Response.<T>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(info)
                .data(data)
                .build();
    }

    public static <T> Response<T> failure(ApiError apiError) {
        return Response.<T>builder()
                .code(apiError.code())
                .info(apiError.message())
                .build();
    }

    public static <T> Response<T> failure(ErrorCode errorCode, String message) {
        return failure(ApiError.of(errorCode, message));
    }

    public static <T> Response<T> failure(ErrorCode errorCode) {
        return failure(ApiError.of(errorCode));
    }
}
