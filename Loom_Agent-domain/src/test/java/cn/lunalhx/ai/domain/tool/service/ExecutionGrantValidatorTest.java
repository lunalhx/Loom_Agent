package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.tool.model.ExecutionGrant;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.FilesystemAccess;
import cn.lunalhx.ai.domain.tool.model.GrantLifetime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.fail;

public class ExecutionGrantValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutionGrantValidator validator = new ExecutionGrantValidator();

    @Test
    public void permitsOnlyCanonicalPathsCoveredByTheGrantedAccess() throws Exception {
        Path workspace = Files.createTempDirectory("grant-workspace").toRealPath();
        Path external = Files.createTempDirectory("grant-external").toRealPath();
        Path child = Files.writeString(external.resolve("readme.txt"), "ok").toRealPath();
        ExecutionProfile base = ExecutionProfile.forRun(CollaborationMode.BUILD, false).withWorkspace(workspace);
        ExecutionProfile granted = new ExecutionProfile(base.kind(), base.workspace(), base.workspaceAccess(),
                base.homeRoot(), base.temporaryRoot(), base.networkAllowed(), base.hostPrivateVisible(),
                List.of(new ExecutionGrant(external, FilesystemAccess.READ, GrantLifetime.SESSION)), base.sandboxBackend());

        validator.validate("run_shell", input(child, "read"), granted);
        assertDenied(input(child, "write"), granted);
        assertDenied(input(workspace.resolve("not-granted.txt"), "read"), granted);
    }

    private com.fasterxml.jackson.databind.JsonNode input(Path path, String access) {
        return mapper.createObjectNode().set("external_access", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("path", path.toString()).put("access", access)));
    }

    private void assertDenied(com.fasterxml.jackson.databind.JsonNode input, ExecutionProfile profile) {
        try {
            validator.validate("run_shell", input, profile);
            fail("execution grant validation should reject this request");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
