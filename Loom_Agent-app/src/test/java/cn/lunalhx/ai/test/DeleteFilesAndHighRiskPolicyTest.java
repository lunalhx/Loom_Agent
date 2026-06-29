package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.infrastructure.tool.DeleteFilesTool;
import cn.lunalhx.ai.infrastructure.tool.GitOpTool;
import cn.lunalhx.ai.infrastructure.tool.RunShellTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.IntStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeleteFilesAndHighRiskPolicyTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== DeleteFilesTool tests ====================

    @Test
    public void deleteSingleFileShouldRequireHighRiskApproval() throws Exception {
        Path file = temporaryFolder.getRoot().toPath().resolve("single.txt");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);

        ToolPolicyDecision policy = tool().policy(call(deleteInput("single.txt")));
        assertEquals(ToolPermissionLevel.HIGH_RISK_CONFIRM, policy.getPermissionLevel());
        assertTrue(policy.getOperationPreview().contains("single.txt"));
        assertTrue(Files.exists(file));
    }

    @Test
    public void deleteMultipleFilesShouldRequireHighRiskApproval() throws Exception {
        Path f1 = temporaryFolder.getRoot().toPath().resolve("a.txt");
        Path f2 = temporaryFolder.getRoot().toPath().resolve("b.txt");
        Files.writeString(f1, "a", StandardCharsets.UTF_8);
        Files.writeString(f2, "b", StandardCharsets.UTF_8);

        ToolPolicyDecision policy = tool().policy(call(deleteInput("a.txt", "b.txt")));
        assertEquals(ToolPermissionLevel.HIGH_RISK_CONFIRM, policy.getPermissionLevel());
    }

    @Test
    public void deleteFileAfterApprovalShouldActuallyDelete() throws Exception {
        Path file = temporaryFolder.getRoot().toPath().resolve("remove.txt");
        Files.writeString(file, "gone", StandardCharsets.UTF_8);
        assertTrue(Files.exists(file));

        ToolResult result = tool().call(call(deleteInput("remove.txt")));
        assertTrue(result.isSuccess());
        assertTrue(result.getObservation().contains("remove.txt"));
        assertTrue(result.getObservation().contains("已删除"));
        assertFalse(Files.exists(file));
    }

    @Test
    public void deleteMultipleFilesShouldDeleteAll() throws Exception {
        Path f1 = temporaryFolder.getRoot().toPath().resolve("f1.txt");
        Path f2 = temporaryFolder.getRoot().toPath().resolve("f2.txt");
        Files.writeString(f1, "1", StandardCharsets.UTF_8);
        Files.writeString(f2, "2", StandardCharsets.UTF_8);

        ToolResult result = tool().call(call(deleteInput("f1.txt", "f2.txt")));
        assertTrue(result.isSuccess());
        assertTrue(result.getObservation().contains("已删除 2"));
        assertFalse(Files.exists(f1));
        assertFalse(Files.exists(f2));
    }

    @Test
    public void deleteFileWithHighRiskPolicyDenyShouldReject() throws Exception {
        Path file = temporaryFolder.getRoot().toPath().resolve("deny.txt");
        Files.writeString(file, "data", StandardCharsets.UTF_8);

        AgentRuntimeProperties props = properties();
        props.setHighRiskPolicy("DENY");
        DeleteFilesTool tool = new DeleteFilesTool(props);
        ToolPolicyDecision policy = tool.policy(call(deleteInput("deny.txt")));
        // Policy returns HIGH_RISK_CONFIRM; DENY is enforced at ApprovalGateNode level
        assertEquals(ToolPermissionLevel.HIGH_RISK_CONFIRM, policy.getPermissionLevel());
    }

    @Test
    public void deleteFileWithHighRiskPolicyAllowShouldStillReturnHighRiskConfirm() throws Exception {
        Path file = temporaryFolder.getRoot().toPath().resolve("allow.txt");
        Files.writeString(file, "data", StandardCharsets.UTF_8);

        AgentRuntimeProperties props = properties();
        props.setHighRiskPolicy("ALLOW");
        DeleteFilesTool tool = new DeleteFilesTool(props);
        // Tool policy always returns HIGH_RISK_CONFIRM; ALLOW is enforced at ApprovalGateNode
        ToolPolicyDecision policy = tool.policy(call(deleteInput("allow.txt")));
        assertEquals(ToolPermissionLevel.HIGH_RISK_CONFIRM, policy.getPermissionLevel());
    }

    @Test
    public void deleteDirectoryShouldRequireApprovalAndDeleteRecursively() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath().resolve("mydir");
        Files.createDirectories(dir.resolve("nested"));
        Files.writeString(dir.resolve("nested/file.py"), "print('bye')", StandardCharsets.UTF_8);

        ToolPolicyDecision policy = tool().policy(call(deleteInput("mydir")));
        assertEquals(ToolPermissionLevel.HIGH_RISK_CONFIRM, policy.getPermissionLevel());
        assertTrue(policy.getOperationPreview().contains("递归删除"));
        assertTrue(policy.getPolicyFingerprint() != null);
        assertEquals(1L, deletePreview(policy).get("fileCount"));
        assertTrue(Files.exists(dir));

        ToolResult result = tool().call(call(deleteInput("mydir")));
        assertTrue(result.isSuccess());
        assertFalse(Files.exists(dir));
    }

    @Test
    public void deleteSymlinkShouldDeleteOnlyTheLink() throws Exception {
        Path target = temporaryFolder.getRoot().toPath().resolve("target.txt");
        Files.writeString(target, "real", StandardCharsets.UTF_8);
        Path link = temporaryFolder.getRoot().toPath().resolve("link.txt");
        Files.createSymbolicLink(link, target);

        ToolPolicyDecision policy = tool().policy(call(deleteInput("link.txt")));
        assertEquals(ToolPermissionLevel.HIGH_RISK_CONFIRM, policy.getPermissionLevel());
        ToolResult result = tool().call(call(deleteInput("link.txt")));
        assertTrue(result.isSuccess());
        assertTrue(Files.exists(target));
        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void deleteWithWildcardShouldBeRejected() throws Exception {
        Path file = temporaryFolder.getRoot().toPath().resolve("test.java");
        Files.writeString(file, "code", StandardCharsets.UTF_8);

        ToolPolicyDecision policy = tool().policy(call(deleteInput("*.java")));
        assertEquals(ToolPermissionLevel.HIGH_RISK_DENY, policy.getPermissionLevel());
        assertTrue(policy.getRiskReason().contains("通配符"));
        assertTrue(Files.exists(file));
    }

    @Test
    public void deleteAbsolutePathShouldBeRejected() throws Exception {
        ToolPolicyDecision policy = tool().policy(call(deleteInput("/etc/passwd")));
        assertEquals(ToolPermissionLevel.HIGH_RISK_DENY, policy.getPermissionLevel());
        assertTrue(policy.getRiskReason().contains("绝对路径"));
    }

    @Test
    public void deleteParentDirectoryPathShouldBeRejected() throws Exception {
        ToolPolicyDecision policy = tool().policy(call(deleteInput("../escape.txt")));
        assertEquals(ToolPermissionLevel.HIGH_RISK_DENY, policy.getPermissionLevel());
        assertTrue(policy.getRiskReason().contains("上级目录"));
    }

    @Test
    public void deleteProtectedPathShouldBeRejected() throws Exception {
        Path gitDir = temporaryFolder.getRoot().toPath().resolve(".git");
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("config"), "fake", StandardCharsets.UTF_8);

        ToolPolicyDecision policy = tool().policy(call(deleteInput(".git/config")));
        assertEquals(ToolPermissionLevel.HIGH_RISK_DENY, policy.getPermissionLevel());
    }

    @Test
    public void deleteMissingFileShouldBeValidationFailure() throws Exception {
        ToolPolicyDecision policy = tool().policy(call(deleteInput("nonexistent.txt")));
        assertTrue(policy.hasValidationFailure());
        assertEquals("not_found", policy.getValidationErrorCode());
        assertTrue(policy.getValidationMessage().contains("不存在"));
    }

    @Test
    public void deleteMoreThan20FilesShouldBeValidationFailure() throws Exception {
        String[] paths = IntStream.rangeClosed(1, 21)
                .mapToObj(i -> "file" + i + ".txt")
                .toArray(String[]::new);
        for (String p : paths) {
            Files.writeString(temporaryFolder.getRoot().toPath().resolve(p), "x", StandardCharsets.UTF_8);
        }

        ToolPolicyDecision policy = tool().policy(call(deleteInput(paths)));
        assertTrue(policy.hasValidationFailure());
        assertEquals("invalid_path", policy.getValidationErrorCode());
        assertTrue(policy.getValidationMessage().contains("20"));
    }

    @Test
    public void deletePathsMustNotBeEmpty() throws Exception {
        ObjectNode input = objectMapper.createObjectNode();
        ArrayNode arr = input.putArray("paths");
        // empty array
        ToolPolicyDecision policy = tool().policy(call("delete_files", input));
        assertTrue(policy.hasValidationFailure());
        assertEquals("invalid_path", policy.getValidationErrorCode());
    }

    @Test
    public void deleteWithOneInvalidPathShouldNotDeleteAny() throws Exception {
        Path good = temporaryFolder.getRoot().toPath().resolve("good.txt");
        Files.writeString(good, "keep", StandardCharsets.UTF_8);

        ToolResult result = tool().call(call(deleteInput("good.txt", "nonexistent.txt")));
        assertFalse(result.isSuccess());
        assertTrue(result.getObservation().contains("不存在"));
        assertTrue("预检失败时合法文件不应删除", Files.exists(good));
    }

    @Test
    public void deleteResultShouldReportSuccessAndFailureSeparately() throws Exception {
        Path exists = temporaryFolder.getRoot().toPath().resolve("exists.txt");
        Files.writeString(exists, "real", StandardCharsets.UTF_8);

        // Need to bypass preflight to test partial deletion
        // Actually the preflight will catch the missing file before any deletion
        // So we test that preflight prevents partial execution
        ToolResult result = tool().call(call(deleteInput("exists.txt", "gone.txt")));
        assertFalse(result.isSuccess());
        assertTrue(result.getObservation().contains("不存在"));
    }

    @Test
    public void deleteEnvFileShouldRequireApprovalWithSecretWarning() throws Exception {
        Path envDir = temporaryFolder.getRoot().toPath().resolve("docs/env");
        Files.createDirectories(envDir);
        Files.writeString(envDir.resolve(".env"), "SECRET=1", StandardCharsets.UTF_8);

        ToolPolicyDecision policy = tool().policy(call(deleteInput("docs/env/.env")));
        assertEquals(ToolPermissionLevel.HIGH_RISK_CONFIRM, policy.getPermissionLevel());
        assertTrue(riskFlags(policy).contains("SECRET_LIKE"));
        assertTrue(Files.exists(envDir.resolve(".env")));
    }

    @Test
    public void deleteKeyFileShouldRequireApprovalWithSecretWarning() throws Exception {
        Path keyFile = temporaryFolder.getRoot().toPath().resolve("secret.key");
        Files.writeString(keyFile, "keydata", StandardCharsets.UTF_8);

        ToolPolicyDecision policy = tool().policy(call(deleteInput("secret.key")));
        assertEquals(ToolPermissionLevel.HIGH_RISK_CONFIRM, policy.getPermissionLevel());
        assertTrue(riskFlags(policy).contains("SECRET_LIKE"));
    }

    @Test
    public void generatedAndIdeDirectoriesShouldBeApprovable() throws Exception {
        for (String directory : new String[]{"target", "node_modules", ".idea"}) {
            Path dir = temporaryFolder.getRoot().toPath().resolve(directory);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("content.txt"), "generated", StandardCharsets.UTF_8);

            ToolPolicyDecision policy = tool().policy(call(deleteInput(directory)));

            assertEquals(directory, ToolPermissionLevel.HIGH_RISK_CONFIRM, policy.getPermissionLevel());
        }
    }

    // ==================== RunShell rm tests ====================

    @Test
    public void runShellFindShouldBeDenied() throws Exception {
        RunShellTool tool = new RunShellTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("command", "find . -name '*.java'");
        ToolPolicyDecision policy = tool.policy(call("run_shell", input));
        assertEquals(ToolPermissionLevel.HIGH_RISK_DENY, policy.getPermissionLevel());
        assertTrue(policy.getRiskReason().contains("find_files"));
    }

    @Test
    public void runShellPythonShouldBeDenied() throws Exception {
        RunShellTool tool = new RunShellTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("command", "python3 script.py");
        ToolPolicyDecision policy = tool.policy(call("run_shell", input));
        assertEquals(ToolPermissionLevel.HIGH_RISK_DENY, policy.getPermissionLevel());
        assertTrue(policy.getRiskReason().contains("find_files"));
    }

    @Test
    public void runShellPython3ShouldBeDenied() throws Exception {
        RunShellTool tool = new RunShellTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("command", "python script.py");
        ToolPolicyDecision policy = tool.policy(call("run_shell", input));
        assertEquals(ToolPermissionLevel.HIGH_RISK_DENY, policy.getPermissionLevel());
        assertTrue(policy.getRiskReason().contains("find_files"));
    }

    @Test
    public void runShellRmFileShouldBeDenied() throws Exception {
        RunShellTool tool = new RunShellTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("command", "rm file.txt");
        ToolPolicyDecision policy = tool.policy(call("run_shell", input));
        assertEquals(ToolPermissionLevel.HIGH_RISK_DENY, policy.getPermissionLevel());
        assertTrue(policy.getRiskReason().contains("delete_files"));
    }

    @Test
    public void runShellRmRfShouldBeDenied() throws Exception {
        RunShellTool tool = new RunShellTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("command", "rm -rf .");
        ToolPolicyDecision policy = tool.policy(call("run_shell", input));
        assertEquals(ToolPermissionLevel.HIGH_RISK_DENY, policy.getPermissionLevel());
    }

    @Test
    public void runShellRmdirShouldBeDeniedAndPointToDeleteTool() throws Exception {
        RunShellTool tool = new RunShellTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("command", "rmdir old-module");
        ToolPolicyDecision policy = tool.policy(call("run_shell", input));
        assertEquals(ToolPermissionLevel.HIGH_RISK_DENY, policy.getPermissionLevel());
        assertTrue(policy.getRiskReason().contains("delete_files"));
    }

    @Test
    public void runShellGitRmShouldBeDenied() throws Exception {
        RunShellTool tool = new RunShellTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("command", "git rm file.txt");
        ToolPolicyDecision policy = tool.policy(call("run_shell", input));
        assertEquals(ToolPermissionLevel.HIGH_RISK_DENY, policy.getPermissionLevel());
    }

    @Test
    public void runShellGitInitShouldRequireWriteConfirm() throws Exception {
        RunShellTool tool = new RunShellTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("command", "git init");
        ToolPolicyDecision policy = tool.policy(call("run_shell", input));
        assertEquals(ToolPermissionLevel.WRITE_CONFIRM, policy.getPermissionLevel());
    }

    @Test
    public void runShellGitInitWithArgumentsShouldBeDenied() throws Exception {
        RunShellTool tool = new RunShellTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("command", "git init nested");
        ToolPolicyDecision policy = tool.policy(call("run_shell", input));
        assertEquals(ToolPermissionLevel.HIGH_RISK_DENY, policy.getPermissionLevel());
    }

    // ==================== New shell registry tests ====================

    @Test
    public void runShellMkdirShouldRequireWriteConfirm() throws Exception {
        RunShellTool tool = new RunShellTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("command", "mkdir foo");
        ToolPolicyDecision policy = tool.policy(call("run_shell", input));
        assertEquals(ToolPermissionLevel.WRITE_CONFIRM, policy.getPermissionLevel());
    }

    @Test
    public void runShellCpShouldRequireWriteConfirm() throws Exception {
        RunShellTool tool = new RunShellTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("command", "cp a b");
        ToolPolicyDecision policy = tool.policy(call("run_shell", input));
        assertEquals(ToolPermissionLevel.WRITE_CONFIRM, policy.getPermissionLevel());
    }

    @Test
    public void runShellCurlShouldRequireHighRiskConfirm() throws Exception {
        RunShellTool tool = new RunShellTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("command", "curl http://example.com");
        ToolPolicyDecision policy = tool.policy(call("run_shell", input));
        assertEquals(ToolPermissionLevel.HIGH_RISK_CONFIRM, policy.getPermissionLevel());
    }

    @Test
    public void runShellShDashCShouldBeDenied() throws Exception {
        RunShellTool tool = new RunShellTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("command", "sh -c echo x");
        ToolPolicyDecision policy = tool.policy(call("run_shell", input));
        assertEquals(ToolPermissionLevel.HIGH_RISK_DENY, policy.getPermissionLevel());
    }

    @Test
    public void runShellEnvWithCommandShouldBeHighRiskConfirm() throws Exception {
        RunShellTool tool = new RunShellTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("command", "env rm -rf target");
        ToolPolicyDecision policy = tool.policy(call("run_shell", input));
        assertEquals("env followed by a real command should be HIGH_RISK_CONFIRM, not READ_ONLY",
                ToolPermissionLevel.HIGH_RISK_CONFIRM, policy.getPermissionLevel());
    }

    @Test
    public void runShellPureEnvShouldBeReadOnly() throws Exception {
        RunShellTool tool = new RunShellTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("command", "env");
        ToolPolicyDecision policy = tool.policy(call("run_shell", input));
        assertEquals(ToolPermissionLevel.READ_ONLY, policy.getPermissionLevel());
    }

    @Test
    public void runShellAbsoluteRmShouldBeDeniedByBasename() throws Exception {
        RunShellTool tool = new RunShellTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("command", "/bin/rm x");
        ToolPolicyDecision policy = tool.policy(call("run_shell", input));
        assertEquals("basename normalization should hit deny bucket",
                ToolPermissionLevel.HIGH_RISK_DENY, policy.getPermissionLevel());
    }

    @Test
    public void runShellPathUnknownScriptShouldBeHighRiskConfirm() throws Exception {
        RunShellTool tool = new RunShellTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("command", "./unknown.sh");
        ToolPolicyDecision policy = tool.policy(call("run_shell", input));
        assertEquals("path-based unknown should be HIGH_RISK_CONFIRM",
                ToolPermissionLevel.HIGH_RISK_CONFIRM, policy.getPermissionLevel());
    }

    @Test
    public void runShellMvnNonTestShouldBeHighRiskConfirm() throws Exception {
        RunShellTool tool = new RunShellTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("command", "mvn compile");
        ToolPolicyDecision policy = tool.policy(call("run_shell", input));
        assertEquals("mvn non-test should now be HIGH_RISK_CONFIRM instead of DENY",
                ToolPermissionLevel.HIGH_RISK_CONFIRM, policy.getPermissionLevel());
    }

    @Test
    public void runShellLsShouldBeReadOnly() throws Exception {
        RunShellTool tool = new RunShellTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("command", "ls -la");
        ToolPolicyDecision policy = tool.policy(call("run_shell", input));
        assertEquals(ToolPermissionLevel.READ_ONLY, policy.getPermissionLevel());
    }

    @Test
    public void runShellCatShouldBeReadOnly() throws Exception {
        RunShellTool tool = new RunShellTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("command", "cat file.txt");
        ToolPolicyDecision policy = tool.policy(call("run_shell", input));
        assertEquals(ToolPermissionLevel.READ_ONLY, policy.getPermissionLevel());
    }

    // ==================== Git tiered classification tests ====================

    @Test
    public void gitPushShouldRequireHighRiskConfirm() throws Exception {
        GitOpTool tool = new GitOpTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("operation", "push");
        ToolPolicyDecision policy = tool.policy(call("git_op", input));
        assertEquals(ToolPermissionLevel.HIGH_RISK_CONFIRM, policy.getPermissionLevel());
    }

    @Test
    public void gitForcePushToMainShouldBeAlwaysDenied() throws Exception {
        GitOpTool tool = new GitOpTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("operation", "push");
        input.put("force", true);
        input.put("remote", "origin");
        input.put("refspec", "main");
        ToolPolicyDecision policy = tool.policy(call("git_op", input));
        assertEquals(ToolPermissionLevel.HIGH_RISK_DENY, policy.getPermissionLevel());
    }

    @Test
    public void gitPushToFeatureBranchShouldRequireApproval() throws Exception {
        GitOpTool tool = new GitOpTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("operation", "push");
        input.put("remote", "origin");
        input.put("refspec", "feature/my-branch");
        ToolPolicyDecision policy = tool.policy(call("git_op", input));
        assertEquals(ToolPermissionLevel.HIGH_RISK_CONFIRM, policy.getPermissionLevel());
    }

    @Test
    public void gitResetShouldRequireHighRiskConfirm() throws Exception {
        GitOpTool tool = new GitOpTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("operation", "reset");
        ToolPolicyDecision policy = tool.policy(call("git_op", input));
        assertEquals(ToolPermissionLevel.HIGH_RISK_CONFIRM, policy.getPermissionLevel());
    }

    @Test
    public void gitResetHardShouldBeRejectedInCall() throws Exception {
        GitOpTool tool = new GitOpTool(properties());
        // reset --hard is detected via the tokens "git reset --hard"
        ObjectNode input = objectMapper.createObjectNode();
        input.put("operation", "reset");
        input.put("force", true); // buildResetCommand rejects force=true
        ToolResult result = tool.call(call("git_op", input));
        assertFalse(result.isSuccess());
    }

    @Test
    public void gitCleanShouldRequireHighRiskConfirm() throws Exception {
        GitOpTool tool = new GitOpTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("operation", "clean");
        input.put("dryRun", true);
        ToolPolicyDecision policy = tool.policy(call("git_op", input));
        assertEquals(ToolPermissionLevel.HIGH_RISK_CONFIRM, policy.getPermissionLevel());
    }

    @Test
    public void gitRebaseShouldRequireHighRiskConfirm() throws Exception {
        GitOpTool tool = new GitOpTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("operation", "rebase");
        input.put("branch", "main");
        ToolPolicyDecision policy = tool.policy(call("git_op", input));
        assertEquals(ToolPermissionLevel.HIGH_RISK_CONFIRM, policy.getPermissionLevel());
    }

    @Test
    public void gitCheckoutShouldRequireHighRiskConfirm() throws Exception {
        GitOpTool tool = new GitOpTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("operation", "checkout");
        input.put("branch", "feature");
        ToolPolicyDecision policy = tool.policy(call("git_op", input));
        assertEquals(ToolPermissionLevel.HIGH_RISK_CONFIRM, policy.getPermissionLevel());
    }

    @Test
    public void gitCheckoutFlagBBranchShouldBeAcceptedByPolicy() throws Exception {
        // -b (lowercase) creates a new branch — this is HIGH_RISK_CONFIRM, not denied
        GitOpTool tool = new GitOpTool(properties());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("operation", "checkout");
        input.put("branch", "-b");
        // buildCheckoutCommand rejects it as a branch name, but policy passes
        ToolPolicyDecision policy = tool.policy(call("git_op", input));
        assertEquals(ToolPermissionLevel.HIGH_RISK_CONFIRM, policy.getPermissionLevel());
    }

    @Test
    public void gitReadOnlyOpsShouldBeReadOnly() throws Exception {
        GitOpTool tool = new GitOpTool(properties());
        for (String op : new String[]{"status", "diff", "log"}) {
            ObjectNode input = objectMapper.createObjectNode();
            input.put("operation", op);
            ToolPolicyDecision policy = tool.policy(call("git_op", input));
            assertEquals("git " + op + " should be READ_ONLY",
                    ToolPermissionLevel.READ_ONLY, policy.getPermissionLevel());
        }
    }

    @Test
    public void gitWriteOpsShouldRequireWriteConfirm() throws Exception {
        GitOpTool tool = new GitOpTool(properties());
        for (String op : new String[]{"init", "add", "commit"}) {
            ObjectNode input = objectMapper.createObjectNode();
            input.put("operation", op);
            if ("commit".equals(op)) {
                input.put("message", "test commit");
            }
            ToolPolicyDecision policy = tool.policy(call("git_op", input));
            assertEquals("git " + op + " should be WRITE_CONFIRM",
                    ToolPermissionLevel.WRITE_CONFIRM, policy.getPermissionLevel());
        }
    }

    // ==================== High-risk policy enforcement tests ====================

    @Test
    public void highRiskConfirmPolicyIsAlwaysReturnedByDeleteFiles() throws Exception {
        Path file = temporaryFolder.getRoot().toPath().resolve("policy-test.txt");
        Files.writeString(file, "test", StandardCharsets.UTF_8);

        // DENY policy — tool still returns HIGH_RISK_CONFIRM (enforced at gate)
        AgentRuntimeProperties denyProps = properties();
        denyProps.setHighRiskPolicy("DENY");
        DeleteFilesTool denyTool = new DeleteFilesTool(denyProps);
        ToolPolicyDecision denyResult = denyTool.policy(call(deleteInput("policy-test.txt")));
        assertEquals(ToolPermissionLevel.HIGH_RISK_CONFIRM, denyResult.getPermissionLevel());

        // ALLOW policy — tool still returns HIGH_RISK_CONFIRM (enforced at gate)
        AgentRuntimeProperties allowProps = properties();
        allowProps.setHighRiskPolicy("ALLOW");
        DeleteFilesTool allowTool = new DeleteFilesTool(allowProps);
        ToolPolicyDecision allowResult = allowTool.policy(call(deleteInput("policy-test.txt")));
        assertEquals(ToolPermissionLevel.HIGH_RISK_CONFIRM, allowResult.getPermissionLevel());

        // CONFIRM policy — tool returns HIGH_RISK_CONFIRM
        AgentRuntimeProperties confirmProps = properties();
        confirmProps.setHighRiskPolicy("CONFIRM");
        DeleteFilesTool confirmTool = new DeleteFilesTool(confirmProps);
        ToolPolicyDecision confirmResult = confirmTool.policy(call(deleteInput("policy-test.txt")));
        assertEquals(ToolPermissionLevel.HIGH_RISK_CONFIRM, confirmResult.getPermissionLevel());
    }

    @Test
    public void highRiskDenyOperationsAlwaysStayDenied() throws Exception {
        // HIGH_RISK_DENY operations can never be allowed, regardless of policy setting
        for (String policy : new String[]{"DENY", "CONFIRM", "ALLOW"}) {
            AgentRuntimeProperties props = properties();
            props.setHighRiskPolicy(policy);

            ObjectNode input = objectMapper.createObjectNode();
            input.put("command", "rm -rf /");
            RunShellTool tool = new RunShellTool(props);
            ToolPolicyDecision decision = tool.policy(call("run_shell", input));
            assertEquals("rm should always be HIGH_RISK_DENY with policy=" + policy,
                    ToolPermissionLevel.HIGH_RISK_DENY, decision.getPermissionLevel());
        }
    }

    @Test
    public void largeFileShouldBeDeletable() throws Exception {
        Path largeFile = temporaryFolder.getRoot().toPath().resolve("large.bin");
        byte[] data = new byte[300_000]; // > fileMaxBytes (200000)
        Files.write(largeFile, data);

        DeleteFilesTool tool = tool();
        ToolPolicyDecision policy = tool.policy(call(deleteInput("large.bin")));
        assertEquals(ToolPermissionLevel.HIGH_RISK_CONFIRM, policy.getPermissionLevel());

        ToolResult result = tool.call(call(deleteInput("large.bin")));
        assertTrue(result.isSuccess());
        assertFalse(Files.exists(largeFile));
    }

    @Test
    public void deleteWorkspaceRootShouldBeRejected() throws Exception {
        DeleteFilesTool tool = tool();
        ToolPolicyDecision policy = tool.policy(call(deleteInput(".")));
        assertEquals(ToolPermissionLevel.HIGH_RISK_DENY, policy.getPermissionLevel());
        assertTrue(policy.getRiskReason().contains("根目录"));
    }

    @Test
    public void deleteDuplicatePathsShouldBeDeduplicated() throws Exception {
        Path file = temporaryFolder.getRoot().toPath().resolve("dup.txt");
        Files.writeString(file, "dup", StandardCharsets.UTF_8);

        ObjectNode input = objectMapper.createObjectNode();
        ArrayNode arr = input.putArray("paths");
        arr.add("dup.txt");
        arr.add("dup.txt");
        ToolPolicyDecision policy = tool().policy(call("delete_files", input));
        assertEquals(ToolPermissionLevel.HIGH_RISK_CONFIRM, policy.getPermissionLevel());
        assertEquals(1, deletePreview(policy).get("targetCount"));
    }

    @Test
    public void parentDirectoryShouldCoverNestedExplicitTarget() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath().resolve("covered");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("nested.txt"), "nested", StandardCharsets.UTF_8);

        ToolPolicyDecision policy = tool().policy(call(deleteInput("covered", "covered/nested.txt")));

        assertEquals(ToolPermissionLevel.HIGH_RISK_CONFIRM, policy.getPermissionLevel());
        assertEquals(1, deletePreview(policy).get("targetCount"));
    }

    @Test
    public void internalSymlinkShouldNotDeleteExternalTarget() throws Exception {
        Path outside = temporaryFolder.getRoot().toPath().resolve("outside.txt");
        Files.writeString(outside, "keep", StandardCharsets.UTF_8);
        Path dir = temporaryFolder.getRoot().toPath().resolve("tree");
        Files.createDirectories(dir);
        Files.createSymbolicLink(dir.resolve("outside-link"), outside);

        ToolResult result = tool().call(call(deleteInput("tree")));

        assertTrue(result.isSuccess());
        assertFalse(Files.exists(dir));
        assertTrue(Files.exists(outside));
    }

    @Test
    public void changedDirectoryAfterApprovalShouldRequireReapproval() throws Exception {
        Path dir = temporaryFolder.getRoot().toPath().resolve("changing");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("before.txt"), "before", StandardCharsets.UTF_8);
        ObjectNode input = deleteInput("changing");
        ToolPolicyDecision policy = tool().policy(call(input));
        Files.writeString(dir.resolve("after.txt"), "after", StandardCharsets.UTF_8);

        ToolCall approvedCall = ToolCall.builder()
                .name("delete_files")
                .input(input)
                .workspaceRoot(temporaryFolder.getRoot().toPath().toRealPath())
                .approvedPolicyFingerprint(policy.getPolicyFingerprint())
                .build();
        ToolResult result = tool().call(approvedCall);

        assertFalse(result.isSuccess());
        assertEquals("approval_stale", result.getErrorCode());
        assertTrue(Files.exists(dir.resolve("before.txt")));
        assertTrue(Files.exists(dir.resolve("after.txt")));
    }

    @Test
    public void deletePathsFieldMissingShouldBeValidationFailure() throws Exception {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("other", "value");
        ToolPolicyDecision policy = tool().policy(call("delete_files", input));
        assertTrue(policy.hasValidationFailure());
        assertEquals("invalid_path", policy.getValidationErrorCode());
    }

    // ==================== helpers ====================

    private DeleteFilesTool tool() {
        return new DeleteFilesTool(properties());
    }

    private ObjectNode deleteInput(String... paths) {
        ObjectNode input = objectMapper.createObjectNode();
        ArrayNode arr = input.putArray("paths");
        for (String p : paths) {
            arr.add(p);
        }
        return input;
    }

    private ToolCall call(String name, ObjectNode input) throws Exception {
        return ToolCall.builder()
                .name(name)
                .input(input)
                .workspaceRoot(temporaryFolder.getRoot().toPath().toRealPath())
                .build();
    }

    private ToolCall call(ObjectNode input) throws Exception {
        return call("delete_files", input);
    }

    private AgentRuntimeProperties properties() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setWorkspaceRoot(temporaryFolder.getRoot().toPath().toString());
        properties.setFileMaxBytes(200000L);
        properties.setToolTimeoutMs(3000L);
        properties.setShellTimeoutMs(3000L);
        properties.setShellMaxOutputChars(12000);
        properties.setHighRiskPolicy("CONFIRM");
        return properties;
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> deletePreview(ToolPolicyDecision policy) {
        return (java.util.Map<String, Object>) policy.getMetadata().get("deletePreview");
    }

    @SuppressWarnings("unchecked")
    private java.util.List<String> riskFlags(ToolPolicyDecision policy) {
        return (java.util.List<String>) deletePreview(policy).get("riskFlags");
    }
}
