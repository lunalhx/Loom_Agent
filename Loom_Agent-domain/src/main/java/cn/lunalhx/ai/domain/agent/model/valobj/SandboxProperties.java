package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class SandboxProperties {
    private String envMode = "BLACKLIST";
    private List<String> envAllowlist = new ArrayList<>(List.of(
            "PATH", "JAVA_HOME", "M2_HOME", "MAVEN_OPTS", "HOME", "LANG", "LC_ALL", "USER", "TERM"));
    private List<String> envExtraBlocklist = new ArrayList<>();
    private int maxCachedConversations = 32;
    private long idleTtlMs = 1_800_000L;
}
