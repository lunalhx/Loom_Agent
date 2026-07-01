package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.api.dto.SkillQueryRequest;
import cn.lunalhx.ai.api.dto.SkillQueryResponse;
import cn.lunalhx.ai.domain.agent.adapter.port.SkillRepository;
import cn.lunalhx.ai.domain.agent.model.entity.SkillCatalog;
import cn.lunalhx.ai.domain.agent.model.entity.SkillDescriptor;
import cn.lunalhx.ai.domain.agent.model.entity.SkillSource;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentWorkspace;
import cn.lunalhx.ai.domain.agent.service.workspace.AgentWorkspaceResolver;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AgentSkillHttpQueryServiceTest {

    private SkillRepository skillRepository;
    private AgentWorkspaceResolver workspaceResolver;
    private AgentRuntimeProperties agentRuntimeProperties;
    private AgentSkillHttpQueryService service;

    @Before
    public void setUp() {
        skillRepository = mock(SkillRepository.class);
        workspaceResolver = mock(AgentWorkspaceResolver.class);
        agentRuntimeProperties = new AgentRuntimeProperties();
        service = new AgentSkillHttpQueryService(skillRepository, workspaceResolver, agentRuntimeProperties);
    }

    @Test
    public void skillsDisabledShouldReturnEmpty() {
        AgentRuntimeProperties.SkillProperties skillProps = new AgentRuntimeProperties.SkillProperties();
        skillProps.setEnabled(false);
        agentRuntimeProperties.setSkills(skillProps);

        List<SkillQueryResponse> result = service.querySkills(new SkillQueryRequest());
        assertTrue(result.isEmpty());
    }

    @Test
    public void skillsEnabledShouldDiscoverAndMap() {
        AgentRuntimeProperties.SkillProperties skillProps = new AgentRuntimeProperties.SkillProperties();
        skillProps.setEnabled(true);
        agentRuntimeProperties.setSkills(skillProps);

        AgentWorkspace workspace = new AgentWorkspace();
        workspace.setRoot(Path.of("/tmp/ws"));
        when(workspaceResolver.resolve(any())).thenReturn(workspace);

        SkillDescriptor skill = new SkillDescriptor(
                "test-skill", "a test skill", null, ">=1.0",
                Map.of(), List.of(), SkillSource.USER, Path.of("/tmp/ws"), null, 0);
        SkillCatalog catalog = new SkillCatalog(Collections.singletonList(skill), Collections.emptyList(), false);
        when(skillRepository.discover(Path.of("/tmp/ws"))).thenReturn(catalog);

        List<SkillQueryResponse> result = service.querySkills(new SkillQueryRequest());

        assertEquals(1, result.size());
        SkillQueryResponse item = result.get(0);
        assertEquals("test-skill", item.getName());
        assertEquals("a test skill", item.getDescription());
        assertEquals("user", item.getSource());
        assertEquals("trusted", item.getTrustState());
        assertEquals(">=1.0", item.getCompatibility());
    }

    @Test
    public void trustStateProjectSkillShouldRequireApproval() {
        AgentRuntimeProperties.SkillProperties skillProps = new AgentRuntimeProperties.SkillProperties();
        skillProps.setEnabled(true);
        agentRuntimeProperties.setSkills(skillProps);

        AgentWorkspace workspace = new AgentWorkspace();
        workspace.setRoot(Path.of("/tmp/ws"));
        when(workspaceResolver.resolve(any())).thenReturn(workspace);

        SkillDescriptor skill = new SkillDescriptor(
                "project-skill", "a project skill", null, null,
                Map.of(), List.of(), SkillSource.PROJECT, Path.of("/tmp/ws"), null, 0);
        SkillCatalog catalog = new SkillCatalog(Collections.singletonList(skill), Collections.emptyList(), false);
        when(skillRepository.discover(Path.of("/tmp/ws"))).thenReturn(catalog);

        List<SkillQueryResponse> result = service.querySkills(new SkillQueryRequest());

        assertEquals(1, result.size());
        assertEquals("approval_required", result.get(0).getTrustState());
    }

    @Test
    public void nullRequestShouldNotFail() {
        AgentRuntimeProperties.SkillProperties skillProps = new AgentRuntimeProperties.SkillProperties();
        skillProps.setEnabled(true);
        agentRuntimeProperties.setSkills(skillProps);

        when(workspaceResolver.resolve(null)).thenReturn(new AgentWorkspace());
        when(skillRepository.discover(any())).thenReturn(SkillCatalog.EMPTY);

        List<SkillQueryResponse> result = service.querySkills(null);
        assertTrue(result.isEmpty());
    }
}
