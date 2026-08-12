package cn.lunalhx.ai.domain.tool.model;

import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PermissionGrantTest {

    @Test
    public void grantIsExactAndCannotExpandTheProfile() {
        ExecutionProfile plan = ExecutionProfile.forRun(CollaborationMode.PLAN, false);
        ExecutionProfile build = ExecutionProfile.forRun(CollaborationMode.BUILD, false);
        PermissionGrant grant = PermissionGrant.issue("exact-call", plan, GrantLifetime.SESSION);

        assertTrue(grant.matches("exact-call", plan));
        assertFalse(grant.matches("different-call", build));
        assertFalse(grant.matches("exact-call", build));
    }
}
