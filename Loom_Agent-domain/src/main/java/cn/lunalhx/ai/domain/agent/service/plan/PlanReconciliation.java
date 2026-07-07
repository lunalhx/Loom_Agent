package cn.lunalhx.ai.domain.agent.service.plan;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentPlanItem;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentPlanItemStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.PlanItemVerification;
import cn.lunalhx.ai.domain.tool.model.ToolOperation;
import org.apache.commons.lang3.StringUtils;

import java.util.Set;
import java.util.stream.Collectors;

public final class PlanReconciliation {

    private PlanReconciliation() {
    }

    /**
     * Reconcile plan item states with execution facts.
     *
     * <ul>
     *   <li>inspect items → complete if the target file(s) have been read</li>
     *   <li>edit items → complete if the target file(s) have been written</li>
     *   <li>verify items → complete if tests have passed after edits</li>
     * </ul>
     */
    public static void reconcile(AgentContext agentContext) {
        if (agentContext.getPlan() == null || agentContext.getPlan().getItems() == null) {
            return;
        }
        Set<String> touched = normalizePaths(agentContext.getTouchedFiles());
        boolean testsPassing = Boolean.TRUE.equals(agentContext.getLastTestPassed())
                && agentContext.getLastTestStep() >= agentContext.getLastWriteStep()
                && !agentContext.isChangedSincePassingTest();
        Set<String> readFiles = agentContext.getReadFiles() != null
                ? normalizePaths(agentContext.getReadFiles()) : Set.of();

        for (AgentPlanItem item : agentContext.getPlan().getItems()) {
            if (item.getStatus() != null && item.getStatus().terminal()) {
                continue;
            }
            String kind = StringUtils.defaultString(item.getKind(), "").toLowerCase();

            if ("inspect".equals(kind) && item.getTargets() != null) {
                boolean allRead = item.getTargets().stream()
                        .map(ToolOperation::normalizePath)
                        .allMatch(readFiles::contains);
                if (allRead && !item.getTargets().isEmpty()) {
                    item.setStatus(AgentPlanItemStatus.COMPLETED);
                    item.setEvidence("目标文件已读取");
                }
            } else if ("edit".equals(kind) && item.getTargets() != null) {
                boolean allWritten = item.getTargets().stream()
                        .map(ToolOperation::normalizePath)
                        .allMatch(touched::contains);
                if (allWritten && !item.getTargets().isEmpty()) {
                    item.setStatus(AgentPlanItemStatus.COMPLETED);
                    item.setEvidence("目标文件已修改: " + String.join(", ", item.getTargets()));
                }
            } else if ("verify".equals(kind)) {
                if (item.getVerification() != null) {
                    PlanItemVerification v = item.getVerification();
                    if (Boolean.TRUE.equals(v.getPassed())) {
                        item.setStatus(AgentPlanItemStatus.COMPLETED);
                        if (item.getEvidence() == null) {
                            item.setEvidence("验证通过: " + StringUtils.defaultString(v.getSummary(), "exit code " + v.getExitCode()));
                        }
                    } else {
                        item.setStatus(AgentPlanItemStatus.BLOCKED);
                        item.setBlocker("验证失败: " + StringUtils.defaultString(v.getSummary(), "exit code " + v.getExitCode()));
                    }
                } else if (testsPassing) {
                    item.setStatus(AgentPlanItemStatus.COMPLETED);
                    item.setEvidence("测试已通过 (exit code 0)");
                }
            }
        }
    }

    private static Set<String> normalizePaths(Set<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return Set.of();
        }
        return paths.stream()
                .map(ToolOperation::normalizePath)
                .collect(Collectors.toSet());
    }
}
