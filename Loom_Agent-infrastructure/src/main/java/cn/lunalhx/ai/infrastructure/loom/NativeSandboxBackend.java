package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfileKind;

import java.util.List;

/** Selects one native sandbox backend; no untrusted host fallback exists. */
final class NativeSandboxBackend {
    private NativeSandboxBackend() { }

    static boolean supported(ExecutionProfile profile) {
        if (profile == null) return false;
        if (profile.kind() == ExecutionProfileKind.DANGER_FULL_ACCESS) return true;
        return SeatbeltSandboxBackend.supported() || BubblewrapSandboxBackend.supported();
    }

    static List<String> wrap(ExecutionProfile profile, List<String> target) {
        if (profile.kind() == ExecutionProfileKind.DANGER_FULL_ACCESS) return target;
        if (SeatbeltSandboxBackend.supported()) {
            return List.of("/usr/bin/sandbox-exec", "-p", SeatbeltSandboxBackend.policy(profile),
                    target.getFirst(), target.get(1), target.get(2));
        }
        if (BubblewrapSandboxBackend.supported()) return BubblewrapSandboxBackend.command(profile, target);
        throw new IllegalStateException("native shell sandbox is unavailable");
    }
}
