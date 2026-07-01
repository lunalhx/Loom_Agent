package cn.lunalhx.ai.domain.agent.service.subagent;

import cn.lunalhx.ai.domain.tool.model.ToolSpec;

public final class SubAgentToolSpecs {

    public static final String SPAWN_AGENTS = "spawn_agents";

    private static final ToolSpec SPAWN_AGENTS_SPEC = ToolSpec.builder()
            .name(SPAWN_AGENTS)
            .description("派生多个隔离上下文的子 Agent 并发执行可独立的子任务，只向主 Agent 回传结构化摘要；只读并发优先，编辑角色只能串行")
            .inputSchema("{\"type\":\"object\",\"properties\":{\"reason\":{\"type\":\"string\",\"minLength\":1,\"description\":\"派生原因\"},\"maxConcurrency\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10,\"default\":2,\"description\":\"最大并发数，受配置上限约束\"},\"returnMode\":{\"type\":\"string\",\"const\":\"summary_only\"},\"tasks\":{\"type\":\"array\",\"minItems\":1,\"items\":{\"type\":\"object\",\"properties\":{\"taskId\":{\"type\":\"string\",\"minLength\":1,\"description\":\"稳定 ID\"},\"role\":{\"type\":\"string\",\"enum\":[\"explorer\",\"reviewer\",\"editor\"]},\"question\":{\"type\":\"string\",\"minLength\":1,\"description\":\"子任务\"},\"pathScope\":{\"type\":\"string\",\"description\":\"可选相对路径\"},\"expectedOutput\":{\"type\":\"string\",\"description\":\"可选输出要求\"},\"maxSteps\":{\"type\":\"integer\",\"minimum\":1,\"description\":\"可选最大步数\"}},\"required\":[\"taskId\",\"role\",\"question\"],\"additionalProperties\":false}}},\"required\":[\"reason\",\"tasks\",\"returnMode\"],\"additionalProperties\":false}")
            .build();

    private SubAgentToolSpecs() {
    }

    public static ToolSpec spawnAgentsSpec() {
        return SPAWN_AGENTS_SPEC;
    }

}
