package cn.lunalhx.ai.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Getter
public enum ResponseCode {

    SUCCESS("0000", "成功"),
    UN_ERROR("0001", "未知失败"),
    ILLEGAL_PARAMETER("0002", "非法参数"),
    AI_MODEL_ERROR("1001", "模型调用失败"),
    AI_MODEL_TIMEOUT("1002", "模型调用超时"),
    AI_OUTPUT_INVALID("1003", "模型输出格式错误"),
    ;

    private String code;
    private String info;

}
