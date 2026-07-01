package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.infrastructure.tool.BackgroundProcessManager;
import cn.lunalhx.ai.infrastructure.tool.CodeSearchTool;
import cn.lunalhx.ai.infrastructure.tool.DeleteFilesTool;
import cn.lunalhx.ai.infrastructure.tool.FindFilesTool;
import cn.lunalhx.ai.infrastructure.tool.GitOpTool;
import cn.lunalhx.ai.infrastructure.tool.ListDirectoryTool;
import cn.lunalhx.ai.infrastructure.tool.LocalCommandExecutor;
import cn.lunalhx.ai.infrastructure.tool.ReadFileTool;
import cn.lunalhx.ai.infrastructure.tool.ReplaceInFileTool;
import cn.lunalhx.ai.infrastructure.tool.RunShellTool;
import cn.lunalhx.ai.infrastructure.tool.WriteFileTool;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import static org.junit.Assert.assertNotNull;

@RunWith(SpringRunner.class)
@SpringBootTest
public class ToolSpringWiringTest {

    @Autowired
    private WorkspacePort workspacePort;

    @Autowired
    private CodeSearchTool codeSearchTool;

    @Autowired
    private DeleteFilesTool deleteFilesTool;

    @Autowired
    private FindFilesTool findFilesTool;

    @Autowired
    private GitOpTool gitOpTool;

    @Autowired
    private ListDirectoryTool listDirectoryTool;

    @Autowired
    private ReadFileTool readFileTool;

    @Autowired
    private ReplaceInFileTool replaceInFileTool;

    @Autowired
    private RunShellTool runShellTool;

    @Autowired
    private WriteFileTool writeFileTool;

    @Autowired
    private LocalCommandExecutor localCommandExecutor;

    @Autowired
    private BackgroundProcessManager backgroundProcessManager;

    @Test
    public void allFileToolsShouldAutowireSuccessfully() {
        assertNotNull(workspacePort);
        assertNotNull(codeSearchTool);
        assertNotNull(deleteFilesTool);
        assertNotNull(findFilesTool);
        assertNotNull(gitOpTool);
        assertNotNull(listDirectoryTool);
        assertNotNull(readFileTool);
        assertNotNull(replaceInFileTool);
        assertNotNull(runShellTool);
        assertNotNull(writeFileTool);
    }

    @Test
    public void localCommandExecutorShouldReceiveBackgroundProcessManager() {
        assertNotNull(localCommandExecutor);
        assertNotNull(backgroundProcessManager);
    }

    @Test
    public void runShellToolAndGitOpToolShouldStartSuccessfully() {
        assertNotNull(runShellTool);
        assertNotNull(gitOpTool);
    }
}
