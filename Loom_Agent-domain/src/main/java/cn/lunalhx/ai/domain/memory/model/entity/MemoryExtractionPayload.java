package cn.lunalhx.ai.domain.memory.model.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

@Value
public class MemoryExtractionPayload {

    String question;
    String finalAnswer;
    int step;
    String workspacePath;

    @JsonCreator
    public MemoryExtractionPayload(
            @JsonProperty("question") String question,
            @JsonProperty("finalAnswer") String finalAnswer,
            @JsonProperty("step") int step,
            @JsonProperty("workspacePath") String workspacePath) {
        this.question = question != null ? question : "";
        this.finalAnswer = finalAnswer != null ? finalAnswer : "";
        this.step = step;
        this.workspacePath = workspacePath != null ? workspacePath : "";
    }
}
