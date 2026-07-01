package cn.lunalhx.ai.test;

import cn.lunalhx.ai.api.dto.SkillQueryRequest;
import cn.lunalhx.ai.api.dto.SkillQueryResponse;
import cn.lunalhx.ai.domain.agent.adapter.port.SkillRepository;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.workspace.AgentWorkspaceResolver;
import cn.lunalhx.ai.trigger.http.AgentSkillController;
import cn.lunalhx.ai.trigger.http.agent.AgentSkillHttpQueryService;
import cn.lunalhx.ai.types.enums.ResponseCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class AgentSkillControllerContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc buildMockMvc(AgentSkillHttpQueryService skillHttpQueryService) {
        AgentSkillController controller = new AgentSkillController(skillHttpQueryService);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    public void querySkillsShouldReturnSuccess() throws Exception {
        AgentSkillHttpQueryService svc = mock(AgentSkillHttpQueryService.class);
        SkillQueryResponse item = SkillQueryResponse.builder()
                .name("test-skill").description("desc").source("user")
                .compatibility(">=1.0").trustState("trusted").diagnostics(List.of())
                .build();
        when(svc.querySkills(any())).thenReturn(List.of(item));

        MockMvc mvc = buildMockMvc(svc);
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/skills/query")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data[0].name").value("test-skill"))
                .andExpect(jsonPath("$.data[0].source").value("user"))
                .andExpect(jsonPath("$.data[0].trustState").value("trusted"));
    }

    @Test
    public void querySkillsEmptyListShouldReturnSuccess() throws Exception {
        AgentSkillHttpQueryService svc = mock(AgentSkillHttpQueryService.class);
        when(svc.querySkills(any())).thenReturn(List.of());

        MockMvc mvc = buildMockMvc(svc);
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/skills/query")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    public void querySkillsNullBodyShouldSucceed() throws Exception {
        AgentSkillHttpQueryService svc = mock(AgentSkillHttpQueryService.class);
        when(svc.querySkills(null)).thenReturn(List.of());

        MockMvc mvc = buildMockMvc(svc);
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/skills/query"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS.getCode()));
    }
}
