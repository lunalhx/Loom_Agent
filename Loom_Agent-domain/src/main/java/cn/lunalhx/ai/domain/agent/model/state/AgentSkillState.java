package cn.lunalhx.ai.domain.agent.model.state;

import cn.lunalhx.ai.domain.skill.model.ActiveSkillSnapshot;
import cn.lunalhx.ai.domain.skill.model.SkillCatalog;

import java.util.List;

/** Run-scoped frozen Skill catalog and activated instruction snapshots. */
public final class AgentSkillState {

    private SkillCatalog catalogSnapshot;
    private List<ActiveSkillSnapshot> activeSkills = List.of();

    public SkillCatalog catalogSnapshot() {
        return catalogSnapshot;
    }

    public List<ActiveSkillSnapshot> activeSkills() {
        return activeSkills;
    }

    public void setCatalogSnapshot(SkillCatalog value) {
        this.catalogSnapshot = value;
    }

    public void setActiveSkills(List<ActiveSkillSnapshot> value) {
        this.activeSkills = value == null ? List.of() : List.copyOf(value);
    }
}
