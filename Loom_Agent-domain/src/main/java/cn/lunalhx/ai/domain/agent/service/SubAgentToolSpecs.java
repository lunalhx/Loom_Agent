package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.tool.model.ToolSpec;

public final class SubAgentToolSpecs {

    public static final String SPAWN_AGENTS = "spawn_agents";

    private static final ToolSpec SPAWN_AGENTS_SPEC = ToolSpec.builder()
            .name(SPAWN_AGENTS)
            .description("派生多个隔离上下文的子 Agent 并发执行可独立的子任务，只向主 Agent 回传结构化摘要；只读并发优先，编辑角色只能串行")
            .inputSchema("{\"reason\":\"派生原因\",\"maxConcurrency\":\"默认 2，受配置上限约束\",\"returnMode\":\"summary_only\",\"tasks\":[{\"taskId\":\"稳定 ID\",\"role\":\"explorer|reviewer|editor\",\"question\":\"子任务\",\"pathScope\":\"可选相对路径\",\"expectedOutput\":\"可选输出要求\",\"maxSteps\":\"可选\"}]}")
            .build();

    private SubAgentToolSpecs() {
    }

    public static ToolSpec spawnAgentsSpec() {
        return SPAWN_AGENTS_SPEC;
    }

}
