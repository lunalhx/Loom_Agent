package cn.lunalhx.ai.domain.tool.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Path;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceRef {

    public static final String LOCAL_PROVIDER = "local";

    private String provider;
    private String location;
    private String displayName;

    public static WorkspaceRef local(Path root, String displayName) {
        return WorkspaceRef.builder()
                .provider(LOCAL_PROVIDER)
                .location(root == null ? null : root.toString())
                .displayName(displayName)
                .build();
    }

    public boolean isLocal() {
        return StringUtils.isBlank(provider) || LOCAL_PROVIDER.equalsIgnoreCase(provider);
    }

    public Path requireLocalPath() {
        if (!isLocal() || StringUtils.isBlank(location)) {
            throw new IllegalStateException("workspace provider 不支持本地文件系统访问：" + provider);
        }
        return Path.of(location);
    }

}
