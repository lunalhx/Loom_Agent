package cn.lunalhx.ai.domain.agent.model.state;

import cn.lunalhx.ai.domain.agent.model.entity.SkillActivation;
import cn.lunalhx.ai.domain.agent.model.entity.SkillCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable skill state: requested, catalog, activated, and approval outcomes.
 */
public final class AgentSkillState {

    private List<String> requestedSkills;
    private SkillCatalog availableSkillCatalog;
    private List<SkillActivation> activatedSkills;
    private String skillCatalogText;
    private List<String> approvedSkillNames;
    private List<String> rejectedSkillNames;

    // -- getters --

    public List<String> requestedSkills() { return requestedSkills; }
    public SkillCatalog availableSkillCatalog() { return availableSkillCatalog; }
    public List<SkillActivation> activatedSkills() { return activatedSkills; }
    public String skillCatalogText() { return skillCatalogText; }
    public List<String> approvedSkillNames() { return approvedSkillNames; }
    public List<String> rejectedSkillNames() { return rejectedSkillNames; }

    // -- package-private mutators --

    public void setRequestedSkills(List<String> v) { this.requestedSkills = v; }
    public void setAvailableSkillCatalog(SkillCatalog v) { this.availableSkillCatalog = v; }
    public void setActivatedSkills(List<SkillActivation> v) { this.activatedSkills = v; }
    public void setSkillCatalogText(String v) { this.skillCatalogText = v; }
    public void setApprovedSkillNames(List<String> v) { this.approvedSkillNames = v; }
    public void setRejectedSkillNames(List<String> v) { this.rejectedSkillNames = v; }
}
