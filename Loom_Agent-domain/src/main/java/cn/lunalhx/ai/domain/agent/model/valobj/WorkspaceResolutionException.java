package cn.lunalhx.ai.domain.agent.model.valobj;

import cn.lunalhx.ai.types.error.ErrorCode;
import lombok.Getter;

@Getter
public class WorkspaceResolutionException extends RuntimeException {

    private final String code;
    private final ErrorCode typedCode;

    public WorkspaceResolutionException(String code, String message) {
        super(message);
        this.code = code;
        this.typedCode = null;
    }

    public WorkspaceResolutionException(ErrorCode typedCode, String message) {
        super(message);
        this.typedCode = typedCode;
        this.code = typedCode.code();
    }
}
