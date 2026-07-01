package cn.lunalhx.ai.trigger.http;

import cn.lunalhx.ai.api.dto.SkillQueryRequest;
import cn.lunalhx.ai.api.dto.SkillQueryResponse;
import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.trigger.http.agent.AgentSkillHttpQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/agent/code")
public class AgentSkillController {

    private final AgentSkillHttpQueryService skillHttpQueryService;

    @PostMapping("/skills/query")
    public Response<List<SkillQueryResponse>> querySkills(@RequestBody(required = false) SkillQueryRequest request) {
        return Response.success(skillHttpQueryService.querySkills(request));
    }
}
