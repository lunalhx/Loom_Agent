package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.GrantLifetime;
import cn.lunalhx.ai.domain.tool.model.PermissionGrant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WorkspacePermissionGrantStoreTest {

    @Test
    public void persistsOnlySaltedExactWorkspaceGrants() throws Exception {
        Path loomHome = Files.createTempDirectory("loom-grants-home");
        Path workspace = Files.createTempDirectory("loom-grants-workspace").toRealPath();
        WorkspacePermissionGrantStore store = new WorkspacePermissionGrantStore(loomHome, new ObjectMapper());
        PermissionGrant grant = PermissionGrant.issue("canonical-call", ExecutionProfile.forRun(
                CollaborationMode.BUILD, false).withWorkspace(workspace), GrantLifetime.WORKSPACE);

        store.append(workspace, grant);

        PermissionGrant restored = store.load(workspace).getFirst();
        assertTrue(restored.matches("canonical-call", grant.executionProfile()));
        assertEquals(GrantLifetime.WORKSPACE, restored.lifetime());
        assertTrue(Files.readString(store.workspaceDirectory(workspace).resolve("grants.json"))
                .contains(grant.saltedCallDigest()));
    }
}
