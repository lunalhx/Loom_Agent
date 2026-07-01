package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.tool.adapter.port.CommandExecutor;
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

import java.lang.reflect.Constructor;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ToolConstructorConstraintTest {

    @Test
    public void fileSystemToolSubclassesShouldHaveExactlyOneConstructor() {
        List<Class<?>> toolClasses = List.of(
                CodeSearchTool.class,
                DeleteFilesTool.class,
                FindFilesTool.class,
                GitOpTool.class,
                ListDirectoryTool.class,
                ReadFileTool.class,
                ReplaceInFileTool.class,
                RunShellTool.class,
                WriteFileTool.class
        );

        for (Class<?> clazz : toolClasses) {
            Constructor<?>[] constructors = clazz.getDeclaredConstructors();
            assertEquals(clazz.getSimpleName() + " should have exactly 1 constructor",
                    1, constructors.length);
        }
    }

    @Test
    public void localCommandExecutorShouldHaveExactlyOneConstructor() {
        Constructor<?>[] constructors = LocalCommandExecutor.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
    }

    @Test
    public void codeSearchToolConstructorShouldAcceptPropertiesAndWorkspacePort() {
        Constructor<?> ctor = CodeSearchTool.class.getDeclaredConstructors()[0];
        Class<?>[] params = ctor.getParameterTypes();
        assertEquals(2, params.length);
        assertEquals(cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties.class, params[0]);
        assertEquals(WorkspacePort.class, params[1]);
    }

    @Test
    public void gitOpToolConstructorShouldAcceptPropertiesWorkspacePortAndCommandExecutor() {
        Constructor<?> ctor = GitOpTool.class.getDeclaredConstructors()[0];
        Class<?>[] params = ctor.getParameterTypes();
        assertEquals(3, params.length);
        assertEquals(cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties.class, params[0]);
        assertEquals(WorkspacePort.class, params[1]);
        assertEquals(CommandExecutor.class, params[2]);
    }

    @Test
    public void runShellToolConstructorShouldAcceptAllFiveDependencies() {
        Constructor<?> ctor = RunShellTool.class.getDeclaredConstructors()[0];
        Class<?>[] params = ctor.getParameterTypes();
        assertEquals(5, params.length);
        assertEquals(cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties.class, params[0]);
        assertEquals(WorkspacePort.class, params[1]);
        assertEquals(CommandExecutor.class, params[2]);
        assertEquals(BackgroundProcessManager.class, params[3]);
        assertEquals(cn.lunalhx.ai.domain.tool.adapter.port.BackgroundShellTaskRepository.class, params[4]);
    }

    @Test
    public void localCommandExecutorConstructorShouldAcceptBackgroundProcessManager() {
        Constructor<?> ctor = LocalCommandExecutor.class.getDeclaredConstructors()[0];
        Class<?>[] params = ctor.getParameterTypes();
        assertEquals(1, params.length);
        assertEquals(BackgroundProcessManager.class, params[0]);
    }
}
