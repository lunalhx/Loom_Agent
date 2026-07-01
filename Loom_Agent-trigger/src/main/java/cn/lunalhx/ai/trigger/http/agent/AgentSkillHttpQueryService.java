package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.api.dto.SkillQueryRequest;
import cn.lunalhx.ai.api.dto.SkillQueryResponse;
import cn.lunalhx.ai.domain.agent.adapter.port.SkillRepository;
import cn.lunalhx.ai.domain.agent.model.entity.SkillCatalog;
import cn.lunalhx.ai.domain.agent.model.entity.SkillDescriptor;
import cn.lunalhx.ai.domain.agent.model.entity.SkillSource;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentWorkspace;
import cn.lunalhx.ai.domain.agent.model.valobj.SkillTrustState;
import cn.lunalhx.ai.domain.agent.service.workspace.AgentWorkspaceResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentSkillHttpQueryService {

    private final SkillRepository skillRepository;
    private final AgentWorkspaceResolver workspaceResolver;
    private final AgentRuntimeProperties agentRuntimeProperties;

    public List<SkillQueryResponse> querySkills(SkillQueryRequest request) {
        AgentRuntimeProperties.SkillProperties skillProps = agentRuntimeProperties.getSkills();
        if (skillProps != null && Boolean.FALSE.equals(skillProps.getEnabled())) {
            return List.of();
        }

        String workspace = request == null ? null : request.getWorkspace();
        AgentWorkspace resolved = workspaceResolver.resolve(workspace);
        SkillCatalog catalog = skillRepository.discover(resolved.getRoot());

        List<SkillQueryResponse> items = new ArrayList<>();
        for (SkillDescriptor skill : catalog.skills()) {
            SkillTrustState trustState = skill.source() == SkillSource.USER
                    ? SkillTrustState.TRUSTED
                    : SkillTrustState.APPROVAL_REQUIRED;
            items.add(SkillQueryResponse.builder()
                    .name(skill.name())
                    .description(skill.description())
                    .source(skill.source().name().toLowerCase())
                    .compatibility(skill.compatibility())
                    .trustState(trustState.name().toLowerCase())
                    .diagnostics(catalog.diagnostics())
                    .build());
        }
        return items;
    }
}
