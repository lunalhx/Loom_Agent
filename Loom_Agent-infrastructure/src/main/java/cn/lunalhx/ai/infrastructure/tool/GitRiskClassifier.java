package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class GitRiskClassifier {

    private static final Set<String> READ_ONLY_OPS = Set.of(
            "status", "diff", "log", "show", "blame", "ls-files", "ls-tree", "rev-parse");
    private static final Set<String> WRITE_OPS = Set.of("add", "commit", "init");
    private static final Set<String> HIGH_RISK_CONFIRM_OPS = Set.of("push", "reset", "clean", "rebase", "checkout");
    private static final Set<String> PROTECTED_BRANCHES = Set.of("main", "master");

    private GitRiskClassifier() {
    }

    public static ToolPolicyDecision classify(List<String> tokens, String command) {
        if (tokens.size() < 2) {
            return ToolPolicyDecision.highRiskDeny("git 子命令不能为空", command);
        }
        String operation = tokens.get(1).toLowerCase(Locale.ROOT);

        if ("init".equals(operation) && tokens.size() > 2) {
            return ToolPolicyDecision.highRiskDeny("git init 仅允许在当前工作区无参数执行", command);
        }

        if (READ_ONLY_OPS.contains(operation)) {
            return ToolPolicyDecision.readOnly("允许的只读 Git 命令 [git_read_only]", command);
        }

        if ("branch".equals(operation)) {
            if (isBranchDelete(tokens)) {
                return ToolPolicyDecision.highRiskDeny("禁止 git branch 删除分支", command);
            }
            if (isReadOnlyBranch(tokens)) {
                return ToolPolicyDecision.readOnly("允许的只读 Git 命令 [git_read_only]", command);
            }
            return ToolPolicyDecision.highRiskConfirm("Git branch 写操作需要高危审批", command);
        }

        if ("tag".equals(operation)) {
            if (isReadOnlyTag(tokens)) {
                return ToolPolicyDecision.readOnly("允许的只读 Git 命令 [git_read_only]", command);
            }
            return ToolPolicyDecision.highRiskConfirm("Git tag 写操作需要高危审批", command);
        }

        if ("remote".equals(operation)) {
            boolean hasVerbose = tokens.stream().anyMatch(
                    t -> "-v".equals(t) || "--verbose".equals(t));
            if (hasVerbose && tokens.size() <= 4) {
                return ToolPolicyDecision.readOnly("允许的只读 Git 命令 [git_read_only]", command);
            }
            return ToolPolicyDecision.highRiskDeny("git remote 仅支持 -v/--verbose", command);
        }

        if ("config".equals(operation)) {
            if (tokens.stream().anyMatch(t -> "--global".equals(t)
                    || "--system".equals(t) || "--file".equals(t))) {
                return ToolPolicyDecision.highRiskDeny(
                        "禁止 git config 修改全局/系统配置", command);
            }
            boolean isReadOnly = tokens.stream().anyMatch(
                    t -> "--list".equals(t) || "-l".equals(t)
                            || "--get".equals(t) || t.startsWith("--get-"));
            if (isReadOnly || tokens.size() <= 2) {
                return ToolPolicyDecision.readOnly("允许的只读 Git 命令 [git_read_only]", command);
            }
            return ToolPolicyDecision.highRiskDeny("不允许的 git config 写操作", command);
        }

        if ("clean".equals(operation) && isDryRun(tokens)) {
            return ToolPolicyDecision.readOnly("git clean --dry-run 为只读操作 [git_read_only]", command);
        }

        if (WRITE_OPS.contains(operation)) {
            return ToolPolicyDecision.writeConfirm("Git " + operation + " 会修改仓库状态，需要人工确认", command);
        }

        if (HIGH_RISK_CONFIRM_OPS.contains(operation)) {
            if (isAlwaysDenied(operation, tokens)) {
                return ToolPolicyDecision.highRiskDeny(
                        "git " + operation + " " + String.join(" ", tokens.subList(2, tokens.size())) + " 被永久拦截",
                        command);
            }
            return ToolPolicyDecision.highRiskConfirm(
                    "Git " + operation + " 是高危操作，需要高危审批", command);
        }

        return ToolPolicyDecision.highRiskDeny("未允许的 Git 子命令：" + operation, command);
    }

    private static boolean isDryRun(List<String> tokens) {
        return tokens.stream().anyMatch(t -> "--dry-run".equals(t) || "-n".equals(t));
    }

    private static boolean isReadOnlyBranch(List<String> tokens) {
        if (tokens.size() == 2) {
            return true;
        }
        for (int i = 2; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (!Set.of("-a", "--all", "-r", "--remotes", "-l", "--list", "--contains", "--merged", "--no-merged").contains(token)
                    && !isPatternArgForPrevious(tokens, i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBranchDelete(List<String> tokens) {
        return tokens.stream().anyMatch(
                t -> "-d".equals(t) || "-D".equals(t) || "--delete".equals(t));
    }

    private static boolean isReadOnlyTag(List<String> tokens) {
        if (tokens.size() == 2) {
            return true;
        }
        for (int i = 2; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (!Set.of("-l", "--list", "-n", "--contains", "--merged", "--no-merged").contains(token)
                    && !isPatternArgForPrevious(tokens, i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPatternArgForPrevious(List<String> tokens, int index) {
        if (index <= 2) {
            return false;
        }
        String previous = tokens.get(index - 1);
        return Set.of("-l", "--list", "--contains", "--merged", "--no-merged").contains(previous);
    }

    private static boolean isAlwaysDenied(String operation, List<String> tokens) {
        return switch (operation) {
            case "reset" -> tokens.contains("--hard");
            case "checkout" -> isCheckoutAlwaysDenied(tokens);
            case "clean" -> isCleanAlwaysDenied(tokens);
            case "push" -> isPushAlwaysDenied(tokens);
            default -> false;
        };
    }

    private static boolean isCheckoutAlwaysDenied(List<String> tokens) {
        boolean hasForce = tokens.contains("-f") || tokens.contains("--force");
        boolean hasBFlag = false;
        boolean hasPathOverride = false;
        for (int i = 2; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.equals("-B")) {
                hasBFlag = true;
            }
            if (token.equals("--") && i + 1 < tokens.size()) {
                hasPathOverride = true;
            }
        }
        return hasForce || hasBFlag || hasPathOverride;
    }

    private static boolean isCleanAlwaysDenied(List<String> tokens) {
        boolean hasDryRun = tokens.contains("--dry-run") || tokens.contains("-n");
        if (hasDryRun) {
            return false;
        }
        return tokens.contains("-f") || tokens.contains("-fd")
                || tokens.contains("-x") || tokens.contains("-X")
                || tokens.stream().anyMatch(t -> t.startsWith("-") && !t.equals("-n") && !t.equals("--dry-run"));
    }

    private static boolean isPushAlwaysDenied(List<String> tokens) {
        boolean hasMirror = tokens.contains("--mirror");
        boolean hasForce = tokens.contains("--force") || tokens.contains("-f")
                || tokens.stream().anyMatch(t -> t.startsWith("+"));
        boolean hasForceWithLease = tokens.contains("--force-with-lease");
        boolean hasDelete = tokens.contains("--delete") || tokens.contains("-d")
                || tokens.stream().anyMatch(t -> t.startsWith(":"));

        if (hasMirror || hasDelete) {
            return true;
        }

        String remote = null;
        String refspec = null;
        for (int i = 2; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.startsWith("-")) {
                continue;
            }
            if (remote == null) {
                remote = token;
            } else if (refspec == null) {
                refspec = token;
            }
        }

        boolean targetsProtectedBranch = false;
        boolean branchAmbiguous = false;
        if (refspec != null && (hasForce || hasForceWithLease)) {
            String branch = extractBranchFromRefspec(refspec);
            if (branch != null && PROTECTED_BRANCHES.contains(branch)) {
                targetsProtectedBranch = true;
            }
        } else if (refspec == null && (hasForce || hasForceWithLease)) {
            branchAmbiguous = true;
        }

        return targetsProtectedBranch || branchAmbiguous;
    }

    private static String extractBranchFromRefspec(String refspec) {
        String cleaned = refspec;
        if (cleaned.startsWith("+")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.startsWith(":")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.contains(":")) {
            cleaned = cleaned.substring(cleaned.indexOf(':') + 1);
        }
        String prefix = "refs/heads/";
        if (cleaned.startsWith(prefix)) {
            return cleaned.substring(prefix.length());
        }
        if (!cleaned.contains("/") && !cleaned.contains("refs/")) {
            return cleaned;
        }
        return null;
    }

    public static boolean isReadOnlyGitOp(String operation) {
        return READ_ONLY_OPS.contains(operation.toLowerCase(Locale.ROOT));
    }

    public static boolean isHighRiskConfirmOp(String operation) {
        return HIGH_RISK_CONFIRM_OPS.contains(operation.toLowerCase(Locale.ROOT));
    }
}
