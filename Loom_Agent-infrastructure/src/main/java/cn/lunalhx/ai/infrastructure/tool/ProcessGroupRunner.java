package cn.lunalhx.ai.infrastructure.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ProcessGroupRunner {

    private ProcessGroupRunner() {}

    public static ProcessGroupResult terminate(ProcessHandle root, long gracefulTimeoutMs) {
        long pid = root.pid();
        List<Long> forceKilled = new ArrayList<>();
        List<Long> residual = new ArrayList<>();

        try {
            List<ProcessHandle> descendants = root.descendants().toList();
            List<ProcessHandle> allTargets = new ArrayList<>();
            allTargets.add(root);
            allTargets.addAll(descendants);

            for (ProcessHandle ph : allTargets) {
                try { ph.destroy(); } catch (Exception ignored) {}
            }

            long deadline = System.currentTimeMillis() + gracefulTimeoutMs;
            while (System.currentTimeMillis() < deadline) {
                boolean anyAlive = false;
                for (ProcessHandle ph : allTargets) {
                    try {
                        if (ph.isAlive()) { anyAlive = true; break; }
                    } catch (Exception ignored) {}
                }
                if (!anyAlive) break;

                List<ProcessHandle> newDescendants = new ArrayList<>();
                for (ProcessHandle ph : allTargets) {
                    try {
                        if (ph.isAlive()) {
                            newDescendants.addAll(ph.descendants().toList());
                        }
                    } catch (Exception ignored) {}
                }
                for (ProcessHandle nd : newDescendants) {
                    if (!allTargets.contains(nd)) {
                        allTargets.add(nd);
                        try { nd.destroy(); } catch (Exception ignored) {}
                    }
                }

                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }

            for (ProcessHandle ph : allTargets) {
                try {
                    if (ph.isAlive()) {
                        ph.destroyForcibly();
                        forceKilled.add(ph.pid());
                    }
                } catch (Exception ignored) {}
            }

            try { Thread.sleep(100); } catch (InterruptedException ignored) {}

            for (ProcessHandle ph : allTargets) {
                try {
                    if (ph.isAlive()) {
                        residual.add(ph.pid());
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            try { root.destroyForcibly(); } catch (Exception ignored) {}
        }

        return new ProcessGroupResult(pid, forceKilled, residual);
    }

    public record ProcessGroupResult(long targetPid, List<Long> forceKilledPids, List<Long> residualPids) {}
}