package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.FeatureFlags;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Every feature flag must map to a real runtime capability. Flags default to
 * enabled; disabling must be observable through the properties that gates
 * the behavior. No flag may be advertised for a capability that does nothing.
 */
public class FeatureFlagsTest {

    @Test
    public void allImplementedFlagsDefaultToEnabled() {
        FeatureFlags flags = new FeatureFlags();
        assertTrue(flags.sessionResume());
        assertTrue(flags.delegateChildRuns());
        assertTrue(flags.approvalGate());
        assertTrue(flags.secretRedaction());
        assertTrue(flags.durableMemory());
        assertTrue(flags.stablePrefixWorkspaceFacts());
        assertTrue(flags.promptCache());
    }

    @Test
    public void flagsAreBoundOnAgentRuntimeProperties() {
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        assertTrue(props.getFeatureFlags().promptCache());
        props.getFeatureFlags().setPromptCache(false);
        assertFalse(props.getFeatureFlags().promptCache());
        assertTrue(props.getFeatureFlags().durableMemory());
    }

    @Test
    public void disabledFlagIsObservableThroughRuntimeProperties() {
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        FeatureFlags flags = props.getFeatureFlags();
        flags.setSessionResume(false);
        flags.setDurableMemory(false);
        flags.setSecretRedaction(false);
        assertFalse(props.getFeatureFlags().sessionResume());
        assertFalse(props.getFeatureFlags().durableMemory());
        assertFalse(props.getFeatureFlags().secretRedaction());
    }
}
