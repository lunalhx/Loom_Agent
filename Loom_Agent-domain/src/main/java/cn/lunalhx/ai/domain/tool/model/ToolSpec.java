package cn.lunalhx.ai.domain.tool.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Contract for a single loom-code tool.
 *
 * <p>Converged to four fields only: name, description, input JSON Schema and
 * {@code risky}. Multi-role visibility, skill permissions and MCP source
 * semantics are removed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolSpec {

    private String name;
    private String description;
    private String inputSchema;
    @Builder.Default
    private boolean risky = false;

    public ToolSpec(String name, String description, String inputSchema) {
        this(name, description, inputSchema, false);
    }

}
