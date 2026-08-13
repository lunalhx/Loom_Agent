package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.tool.model.ToolCapabilityEnvelope;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;

import java.util.List;
import java.util.Set;

/**
 * MCP and other unverifiable external tools: incomplete or untrusted effect
 * envelopes cannot be reconciled from Repository State. A missing contract is
 * fail-closed — capability shrink must not drop an Interrupted Tool Call out
 * of Ambiguity Review. Shell stays with {@link ShellTools}; {@code delegate}
 * stays outside until its recovery ticket.
 */
public final class UnverifiableExternalTools {

    private static final Set<String> OTHER_RECOVERY_ADAPTERS = Set.of("run_shell", "delegate");

    private UnverifiableExternalTools() {
    }

    public static boolean isUnverifiableExternal(String toolName, List<ToolSpec> contracts) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        if (ObservationTools.isObservation(toolName)
                || FileMutationTools.isFileMutation(toolName)
                || OTHER_RECOVERY_ADAPTERS.contains(toolName)) {
            return false;
        }
        ToolSpec spec = find(toolName, contracts);
        if (spec == null) {
            return true;
        }
        ToolCapabilityEnvelope envelope = spec.getCapabilityEnvelope();
        return envelope == null || !envelope.trusted() || !envelope.complete();
    }

    private static ToolSpec find(String toolName, List<ToolSpec> contracts) {
        if (contracts == null) {
            return null;
        }
        for (ToolSpec spec : contracts) {
            if (spec != null && toolName.equals(spec.getName())) {
                return spec;
            }
        }
        return null;
    }
}
