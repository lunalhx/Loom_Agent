package cn.lunalhx.ai.domain.tool.model;

import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertFalse;

public class ExecutionProfileTest {

    @Test
    public void ordinaryBuildDoesNotGrantExplicitOutboundDisclosure() {
        EffectProfile outbound = new EffectProfile(Set.of(ToolEffect.EXTERNAL_READ),
                OutboundDisclosure.PRESENT, true);
        assertFalse(ExecutionProfile.forRun(CollaborationMode.BUILD, false).allows(outbound));
    }

    @Test
    public void planDoesNotGrantExternalReadsBeforeAnExecutionGrant() {
        EffectProfile externalRead = new EffectProfile(Set.of(ToolEffect.EXTERNAL_READ),
                OutboundDisclosure.NONE, true);
        assertFalse(ExecutionProfile.forRun(CollaborationMode.PLAN, false).allows(externalRead));
    }
}
