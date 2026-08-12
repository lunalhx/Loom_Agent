package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RunShellToolProfileTest {
    private final RunShellTool tool = new RunShellTool(null);

    @Test
    public void planAndDelegateAcceptOnlyConservativeReadOnlyCommands() {
        ExecutionProfile plan = ExecutionProfile.forRun(CollaborationMode.PLAN, false);
        assertTrue(tool.assessEffect(call("git status --short"), plan).trusted());
        assertFalse(tool.assessEffect(call("git reset --hard"), plan).trusted());
        assertFalse(tool.assessEffect(call("cat $HOME/.ssh/id_rsa"), plan).trusted());
    }

    private ToolCall call(String command) {
        return ToolCall.builder().name("run_shell").input(JsonNodeFactory.instance.objectNode()
                .put("command", command)).build();
    }
}
