package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.tool.model.ExecutionGrantRequest;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfileKind;
import cn.lunalhx.ai.domain.tool.model.GrantLifetime;
import cn.lunalhx.ai.domain.tool.model.PermissionDecision;
import cn.lunalhx.ai.domain.tool.service.AuthorizationDisplay;
import cn.lunalhx.ai.domain.tool.service.PermissionPrompt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * CLI permission prompt coordination. Interactive I/O is injectable so the
 * decision parsing can be tested without System.in.
 */
public final class CliApprovalPrompt implements PermissionPrompt {
    private final BufferedReader reader;
    private final boolean interactive;

    public CliApprovalPrompt(boolean interactive) {
        this(interactive, new InputStreamReader(System.in, StandardCharsets.UTF_8));
    }

    public CliApprovalPrompt(boolean interactive, Reader input) {
        this.interactive = interactive;
        this.reader = new BufferedReader(input);
    }

    @Override
    public GrantLifetime ask(AuthorizationDisplay display, PermissionDecision decision) {
        if (!interactive) {
            return null;
        }
        System.out.println();
        System.out.println("permission required: " + display.toolName() + " " + display.normalizedSummary());
        if (display.profile().kind() == ExecutionProfileKind.DANGER_FULL_ACCESS) {
            System.out.println("FULL ACCESS: command runs without the ordinary sandbox.");
        }
        System.out.print(decision.perCallOnly()
                ? "allow once? [o/N] " : "allow once/session/workspace? [o/s/w/N] ");
        System.out.flush();
        try {
            String line = reader.readLine();
            if (line == null) {
                return null;
            }
            String choice = line.strip().toLowerCase();
            if (decision.perCallOnly() && !("o".equals(choice) || "once".equals(choice))) {
                return null;
            }
            return switch (choice) {
                case "o", "once" -> GrantLifetime.ONCE;
                case "s", "session" -> GrantLifetime.SESSION;
                case "w", "workspace" -> GrantLifetime.WORKSPACE;
                default -> null;
            };
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public GrantLifetime askExecutionGrant(ExecutionGrantRequest request) {
        if (!interactive) {
            return null;
        }
        System.out.println();
        System.out.println("external filesystem access required: " + request.access().name().toLowerCase()
                + " " + request.canonicalPath());
        System.out.print("allow once/session/workspace? [o/s/w/N] ");
        System.out.flush();
        try {
            String line = reader.readLine();
            if (line == null) {
                return null;
            }
            return switch (line.strip().toLowerCase()) {
                case "o", "once" -> GrantLifetime.ONCE;
                case "s", "session" -> GrantLifetime.SESSION;
                case "w", "workspace" -> GrantLifetime.WORKSPACE;
                default -> null;
            };
        } catch (IOException e) {
            return null;
        }
    }
}
