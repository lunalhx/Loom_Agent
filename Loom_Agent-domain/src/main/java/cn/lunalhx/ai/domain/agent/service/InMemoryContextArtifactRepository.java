package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryContextArtifactRepository implements ContextArtifactRepository {

    private final Map<String, ContextArtifact> artifacts = new ConcurrentHashMap<>();

    @Override
    public ContextArtifact save(ContextArtifact artifact) {
        artifacts.put(artifact.getArtifactId(), artifact);
        return artifact;
    }

    @Override
    public Optional<ContextArtifact> findByArtifactIdAndRootRunId(String artifactId, String rootRunId) {
        ContextArtifact artifact = artifacts.get(artifactId);
        if (artifact == null || !StringUtils.equals(artifact.getRootRunId(), rootRunId)) {
            return Optional.empty();
        }
        return Optional.of(artifact);
    }

    @Override
    public List<ContextArtifact> listByRootRunId(String rootRunId) {
        return artifacts.values().stream()
                .filter(artifact -> StringUtils.equals(artifact.getRootRunId(), rootRunId))
                .sorted(Comparator.comparing(ContextArtifact::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Override
    public List<ContextArtifact> searchByRootRunId(String rootRunId, String query, int limit) {
        String needle = StringUtils.lowerCase(StringUtils.trimToEmpty(query));
        if (StringUtils.isBlank(needle)) {
            return List.of();
        }
        List<ContextArtifact> matches = new ArrayList<>();
        for (ContextArtifact artifact : listByRootRunId(rootRunId)) {
            if (StringUtils.contains(StringUtils.lowerCase(artifact.getPreview()), needle)
                    || StringUtils.contains(StringUtils.lowerCase(artifact.getArtifactId()), needle)) {
                matches.add(artifact);
            }
            if (matches.size() >= Math.max(1, limit)) {
                break;
            }
        }
        return matches;
    }

}
