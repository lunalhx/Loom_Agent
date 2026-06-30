package cn.lunalhx.ai.domain.agent.adapter.port;

import cn.lunalhx.ai.domain.agent.model.entity.SkillCatalog;
import cn.lunalhx.ai.domain.agent.model.entity.SkillDescriptor;
import cn.lunalhx.ai.domain.agent.model.entity.SkillResourceManifest;

import java.nio.file.Path;

public interface SkillRepository {

    SkillCatalog discover(Path workspaceRoot);

    SkillDescriptor resolve(String name, Path workspaceRoot);

    SkillResourceManifest buildManifest(SkillDescriptor descriptor);

    String readSkillContent(SkillDescriptor descriptor);

    String readResourceText(SkillDescriptor descriptor, String relativePath, int offset, int maxChars);

    byte[] readResourceBytes(SkillDescriptor descriptor, String relativePath);
}
