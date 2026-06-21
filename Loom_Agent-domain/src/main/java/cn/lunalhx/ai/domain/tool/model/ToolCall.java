package cn.lunalhx.ai.domain.tool.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCall {

    private String name;
    private JsonNode input;
    private Path workspaceRoot;

}
