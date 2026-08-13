package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.GrantLifetime;
import cn.lunalhx.ai.domain.tool.model.PermissionAction;
import cn.lunalhx.ai.domain.tool.model.PermissionDecision;
import cn.lunalhx.ai.domain.tool.service.AuthorizationDisplay;
import org.junit.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Independently testable CLI approval coordination — no System.in. */
public class CliApprovalPromptTest {

    @Test
    public void interactiveOnceChoiceAuthorizesSingleCall() {
        CliApprovalPrompt prompt = new CliApprovalPrompt(true, new StringReader("o\n"));
        GrantLifetime lifetime = prompt.ask(display(), decision(false));
        assertEquals(GrantLifetime.ONCE, lifetime);
    }

    @Test
    public void nonInteractiveRejectsWithoutReading() {
        CliApprovalPrompt prompt = new CliApprovalPrompt(false, new StringReader("o\n"));
        assertNull(prompt.ask(display(), decision(false)));
    }

    @Test
    public void executionGrantParsesSessionChoice() {
        CliApprovalPrompt prompt = new CliApprovalPrompt(true, new StringReader("s\n"));
        GrantLifetime lifetime = prompt.askExecutionGrant(
                new cn.lunalhx.ai.domain.tool.model.ExecutionGrantRequest(
                        java.nio.file.Path.of("/tmp/x"),
                        cn.lunalhx.ai.domain.tool.model.FilesystemAccess.READ));
        assertEquals(GrantLifetime.SESSION, lifetime);
    }

    private AuthorizationDisplay display() {
        return new AuthorizationDisplay("run_shell", "echo x", List.of(), "/ws",
                ExecutionProfile.forRun(cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD, false));
    }

    private PermissionDecision decision(boolean perCallOnly) {
        return new PermissionDecision(PermissionAction.ASK, "ask", List.of(), List.of(), perCallOnly);
    }
}
