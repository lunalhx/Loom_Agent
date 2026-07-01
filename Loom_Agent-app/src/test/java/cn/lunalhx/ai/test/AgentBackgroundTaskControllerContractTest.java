package cn.lunalhx.ai.test;

import cn.lunalhx.ai.api.dto.BackgroundTaskDetailResponse;
import cn.lunalhx.ai.api.dto.BackgroundTaskResponse;
import cn.lunalhx.ai.trigger.http.AgentBackgroundTaskController;
import cn.lunalhx.ai.trigger.http.agent.AgentBackgroundTaskHttpService;
import cn.lunalhx.ai.types.enums.ResponseCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class AgentBackgroundTaskControllerContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc buildMockMvc(AgentBackgroundTaskHttpService backgroundTaskHttpService) {
        AgentBackgroundTaskController controller =
                new AgentBackgroundTaskController(backgroundTaskHttpService);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    public void listTasksShouldReturnSuccess() throws Exception {
        AgentBackgroundTaskHttpService svc = mock(AgentBackgroundTaskHttpService.class);
        BackgroundTaskResponse task = BackgroundTaskResponse.builder()
                .taskId("t-1").runId("r-1").status("SUCCEEDED").command("ls").build();
        when(svc.listTasks("r-1")).thenReturn(List.of(task));

        MockMvc mvc = buildMockMvc(svc);
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/agent/code/runs/r-1/background-tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data[0].taskId").value("t-1"))
                .andExpect(jsonPath("$.data[0].status").value("SUCCEEDED"));
    }

    @Test
    public void getTaskDetailShouldReturnSuccess() throws Exception {
        AgentBackgroundTaskHttpService svc = mock(AgentBackgroundTaskHttpService.class);
        BackgroundTaskDetailResponse detail = BackgroundTaskDetailResponse.builder()
                .taskId("t-1").runId("r-1").status("SUCCEEDED")
                .stdoutChunk("hello").stdoutOffset(5).stdoutEof(true)
                .stderrChunk("").stderrOffset(0).stderrEof(true)
                .command("echo hello").build();
        when(svc.getTaskDetail(anyString(), anyString(), anyLong(), anyLong(), anyInt()))
                .thenReturn(detail);

        MockMvc mvc = buildMockMvc(svc);
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/agent/code/runs/r-1/background-tasks/t-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.taskId").value("t-1"))
                .andExpect(jsonPath("$.data.stdoutChunk").value("hello"));
    }

    @Test
    public void getTaskDetailNotFoundShouldReturnErrorCode() throws Exception {
        AgentBackgroundTaskHttpService svc = mock(AgentBackgroundTaskHttpService.class);
        when(svc.getTaskDetail(anyString(), anyString(), anyLong(), anyLong(), anyInt()))
                .thenReturn(null);

        MockMvc mvc = buildMockMvc(svc);
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/agent/code/runs/r-1/background-tasks/missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("BACKGROUND_TASK_NOT_FOUND"));
    }

    @Test
    public void cancelTaskShouldReturnSuccess() throws Exception {
        AgentBackgroundTaskHttpService svc = mock(AgentBackgroundTaskHttpService.class);
        BackgroundTaskResponse task = BackgroundTaskResponse.builder()
                .taskId("t-1").runId("r-1").status("CANCELLED").build();
        when(svc.cancelTask("r-1", "t-1")).thenReturn(task);

        MockMvc mvc = buildMockMvc(svc);
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/runs/r-1/background-tasks/t-1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.taskId").value("t-1"))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    public void cancelTaskNotFoundShouldReturnErrorCode() throws Exception {
        AgentBackgroundTaskHttpService svc = mock(AgentBackgroundTaskHttpService.class);
        when(svc.cancelTask("r-1", "missing")).thenReturn(null);

        MockMvc mvc = buildMockMvc(svc);
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/agent/code/runs/r-1/background-tasks/missing/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("BACKGROUND_TASK_NOT_FOUND"));
    }
}
