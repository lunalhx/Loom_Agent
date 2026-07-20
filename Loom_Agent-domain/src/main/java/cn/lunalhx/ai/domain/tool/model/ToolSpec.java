package cn.lunalhx.ai.domain.tool.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolSpec {

    private String name;
    private String description;
    private String inputSchema;
    @Builder.Default
    private boolean readOnly = false;
    @Builder.Default
    private ToolChildVisibility childVisibility = ToolChildVisibility.EDITOR_ONLY;
    @Builder.Default
    private ToolSource source = ToolSource.BUILTIN;

    public ToolSpec(String name, String description, String inputSchema) {
        this(name, description, inputSchema, false, ToolChildVisibility.EDITOR_ONLY, ToolSource.BUILTIN);
    }

}
