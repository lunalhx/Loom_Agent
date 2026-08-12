package cn.lunalhx.ai.domain.tool.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Contract for a single loom-code tool and its independent governance metadata. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolSpec {

    private String name;
    private String description;
    private String inputSchema;
    @Builder.Default
    private ToolCapabilityEnvelope capabilityEnvelope = ToolCapabilityEnvelope.untrustedUnknown();

    public ToolSpec(String name, String description, String inputSchema) {
        this(name, description, inputSchema, ToolCapabilityEnvelope.untrustedUnknown());
    }

}
