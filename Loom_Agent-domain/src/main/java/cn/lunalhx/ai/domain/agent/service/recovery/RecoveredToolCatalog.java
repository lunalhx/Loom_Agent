package cn.lunalhx.ai.domain.agent.service.recovery;

import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Recovery capability may only stay equal or shrink. Same-name tools whose
 * input schema or effect envelope drifted are removed, not substituted.
 */
public final class RecoveredToolCatalog {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RecoveredToolCatalog() {
    }

    public static List<ToolSpec> keepCompatible(List<ToolSpec> frozen, List<ToolSpec> live) {
        if (frozen == null || frozen.isEmpty() || live == null || live.isEmpty()) {
            return List.of();
        }
        Map<String, ToolSpec> liveByName = new LinkedHashMap<>();
        for (ToolSpec spec : live) {
            if (spec != null && spec.getName() != null && !spec.getName().isBlank()) {
                liveByName.putIfAbsent(spec.getName(), spec);
            }
        }
        List<ToolSpec> recovered = new ArrayList<>();
        for (ToolSpec contract : frozen) {
            if (contract == null || contract.getName() == null || contract.getName().isBlank()) {
                continue;
            }
            ToolSpec candidate = liveByName.get(contract.getName());
            if (candidate != null && compatible(contract, candidate)) {
                recovered.add(candidate);
            }
        }
        return List.copyOf(recovered);
    }

    static boolean compatible(ToolSpec frozen, ToolSpec live) {
        if (frozen.getCapabilityEnvelope() == null || live.getCapabilityEnvelope() == null) {
            return false;
        }
        return Objects.equals(frozen.getName(), live.getName())
                && schemaEquals(frozen.getInputSchema(), live.getInputSchema())
                && Objects.equals(frozen.getCapabilityEnvelope(), live.getCapabilityEnvelope());
    }

    private static boolean schemaEquals(String left, String right) {
        if (Objects.equals(left, right)) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        try {
            JsonNode leftNode = JSON.readTree(left);
            JsonNode rightNode = JSON.readTree(right);
            return leftNode.equals(rightNode);
        } catch (Exception e) {
            return false;
        }
    }
}
