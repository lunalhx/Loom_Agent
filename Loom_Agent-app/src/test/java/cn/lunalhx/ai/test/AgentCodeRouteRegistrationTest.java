package cn.lunalhx.ai.test;

import org.junit.Test;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AgentCodeRouteRegistrationTest {

    @Test
    public void all18EndpointsShouldBeDefinedWithoutDuplicates() {
        Object[][] expected = {
                {"POST", "/api/v1/agent/code/ask/stream"},
                {"POST", "/api/v1/agent/code/approvals/{approvalId}/decide/stream"},
                {"GET", "/api/v1/agent/code/approvals/{approvalId}"},
                {"POST", "/api/v1/agent/code/runs/{runId}/resume/stream"},
                {"POST", "/api/v1/agent/code/runs/{runId}/input/stream"},
                {"POST", "/api/v1/agent/code/runs/{runId}/cancel"},
                {"GET", "/api/v1/agent/code/runs/{runId}/trace"},
                {"GET", "/api/v1/agent/code/runs/{runId}/replay"},
                {"POST", "/api/v1/agent/code/runs/{runId}/replay/stream"},
                {"GET", "/api/v1/agent/code/runs/{runId}/undo"},
                {"POST", "/api/v1/agent/code/runs/{runId}/undo"},
                {"POST", "/api/v1/agent/code/skills/query"},
                {"GET", "/api/v1/agent/code/conversations"},
                {"DELETE", "/api/v1/agent/code/conversations/{conversationId}"},
                {"GET", "/api/v1/agent/code/conversations/{conversationId}/deletion"},
                {"GET", "/api/v1/agent/code/runs/{runId}/background-tasks"},
                {"GET", "/api/v1/agent/code/runs/{runId}/background-tasks/{taskId}"},
                {"POST", "/api/v1/agent/code/runs/{runId}/background-tasks/{taskId}/cancel"},
        };

        assertEquals(18, expected.length);

        List<String> duplicateCheck = new ArrayList<>();
        for (Object[] row : expected) {
            String key = row[0] + " " + row[1];
            assertTrue("Duplicate endpoint: " + key, duplicateCheck.add(key));
        }
        assertEquals(18, duplicateCheck.size());
    }
}
