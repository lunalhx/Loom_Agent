package cn.lunalhx.ai.domain.tool.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Repository observation semantics supported by precise Plan Evidence. */
public enum EvidenceObservationType {
    READ_FILE("read_file"),
    LIST_FILES("list_files"),
    SEARCH("search");

    private final String toolName;

    EvidenceObservationType(String toolName) {
        this.toolName = toolName;
    }

    @JsonValue
    public String toolName() {
        return toolName;
    }

    @JsonCreator
    public static EvidenceObservationType from(String value) {
        for (EvidenceObservationType type : values()) {
            if (type.toolName.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown Evidence observation type: " + value);
    }
}
