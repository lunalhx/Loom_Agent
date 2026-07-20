package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolAssembler {

    private ToolAssembler() {
    }

    @SafeVarargs
    public static List<AgentTool> assemble(Collection<? extends AgentTool>... sources) {
        Map<String, AgentTool> assembled = new LinkedHashMap<>();
        for (Collection<? extends AgentTool> source : sources) {
            if (source == null) {
                continue;
            }
            for (AgentTool tool : source) {
                ToolSpec spec = tool.spec();
                String name = spec == null ? null : spec.getName();
                AgentTool existing = assembled.putIfAbsent(name, tool);
                if (existing != null) {
                    if (existing.spec().isReadOnly() != spec.isReadOnly()) {
                        throw new IllegalStateException("工具 " + name + " 的 readOnly 声明冲突");
                    }
                    throw new IllegalStateException("重复的工具名：" + name);
                }
            }
        }
        return List.copyOf(new ArrayList<>(assembled.values()));
    }
}
