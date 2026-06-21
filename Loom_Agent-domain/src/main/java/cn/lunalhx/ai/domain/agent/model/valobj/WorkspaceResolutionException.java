package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.Getter;

@Getter
public class WorkspaceResolutionException extends RuntimeException {

    private final String code;

    public WorkspaceResolutionException(String code, String message) {
        super(message);
        this.code = code;
    }

}
