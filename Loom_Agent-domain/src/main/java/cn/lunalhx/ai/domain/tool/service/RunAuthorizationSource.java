package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.tool.model.PermissionAction;
import cn.lunalhx.ai.domain.tool.model.PermissionPolicySnapshot;
import cn.lunalhx.ai.domain.tool.model.PermissionRule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Atomically validates the on-disk project policy before a root run starts. */
public final class RunAuthorizationSource {
    private static final Set<String> RULE_FIELDS = Set.of("id", "tool", "action", "match");
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final WorkspacePermissionGrantStore workspaceGrants;

    public RunAuthorizationSource() {
        this(new WorkspacePermissionGrantStore());
    }

    RunAuthorizationSource(WorkspacePermissionGrantStore workspaceGrants) {
        this.workspaceGrants = workspaceGrants;
    }

    public PermissionPolicySnapshot load(Path workspace, PermissionAction defaultAction) {
        Path source = workspace.resolve(".loom").resolve("permissions.yml");
        Path userSource = workspaceGrants.policyFile(workspace);
        try {
            List<PermissionRule> rules = new ArrayList<>(builtIns());
            List<String> digests = new ArrayList<>(List.of("builtin"));
            Set<String> ids = new java.util.HashSet<>();
            builtIns().forEach(rule -> ids.add(rule.id()));
            loadRules(source, "project", false, rules, digests, ids);
            loadRules(userSource, "user", true, rules, digests, ids);
            return new PermissionPolicySnapshot(defaultAction, rules, digests);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid permission source: " + e.getMessage(), e);
        }
    }

    public List<cn.lunalhx.ai.domain.tool.model.PermissionGrant> loadWorkspaceGrants(Path workspace) {
        return workspaceGrants.load(workspace);
    }

    public List<cn.lunalhx.ai.domain.tool.model.ExecutionGrant> loadWorkspaceExecutionGrants(Path workspace) {
        return workspaceGrants.loadExecution(workspace);
    }

    private void loadRules(Path source, String sourceId, boolean allowsAllow,
                           List<PermissionRule> rules, List<String> digests, Set<String> ids) throws Exception {
        if (!Files.exists(source)) return;
        if (Files.isSymbolicLink(source)) throw new IllegalArgumentException(sourceId + " permission source must not be a symlink");
        byte[] bytes = Files.readAllBytes(source);
        JsonNode root = yaml.readTree(bytes);
        if (root == null || !root.isObject() || root.path("version").asInt(-1) != 1) {
            throw new IllegalArgumentException(source + " requires version: 1");
        }
        rejectUnknown(root, Set.of("version", "rules"), source);
        JsonNode rulesNode = root.path("rules");
        if (!rulesNode.isArray()) throw new IllegalArgumentException(source + " requires rules array");
        for (JsonNode rule : rulesNode) {
            rejectUnknown(rule, RULE_FIELDS, source);
            String id = required(rule, "id", source);
            if (!ids.add(id)) throw new IllegalArgumentException("duplicate permission rule id " + id);
            PermissionAction action = PermissionAction.valueOf(required(rule, "action", source).toUpperCase());
            if (!allowsAllow && action == PermissionAction.ALLOW) {
                throw new IllegalArgumentException("project rules may only ASK or DENY");
            }
            JsonNode match = rule.path("match");
            if (!match.isObject() || match.size() != 1) throw new IllegalArgumentException("rule " + id + " requires one matcher");
            Iterator<String> names = match.fieldNames();
            String kind = names.next();
            PermissionRule.MatcherKind matcher = switch (kind) {
                case "tool" -> PermissionRule.MatcherKind.TOOL;
                case "exact_call" -> PermissionRule.MatcherKind.EXACT_CALL;
                case "shell_prefix" -> PermissionRule.MatcherKind.SHELL_PREFIX;
                case "path_prefix" -> PermissionRule.MatcherKind.PATH_PREFIX;
                case "domain" -> PermissionRule.MatcherKind.DOMAIN;
                default -> throw new IllegalArgumentException("unknown matcher " + kind);
            };
            rules.add(new PermissionRule(id, sourceId, rule.path("tool").asText(""), matcher,
                    match.path(kind).asText(), action));
        }
        digests.add(sourceId + ":" + digest(bytes));
    }

    private List<PermissionRule> builtIns() {
        return List.of(new PermissionRule("builtin-read-file", "builtin", "read_file", PermissionRule.MatcherKind.TOOL, "read_file", PermissionAction.ALLOW),
                new PermissionRule("builtin-list-files", "builtin", "list_files", PermissionRule.MatcherKind.TOOL, "list_files", PermissionAction.ALLOW),
                new PermissionRule("builtin-search", "builtin", "search", PermissionRule.MatcherKind.TOOL, "search", PermissionAction.ALLOW),
                new PermissionRule("builtin-delegate", "builtin", "delegate", PermissionRule.MatcherKind.TOOL, "delegate", PermissionAction.ALLOW));
    }

    private static String required(JsonNode node, String key, Path source) {
        String value = node.path(key).asText("");
        if (value.isBlank()) throw new IllegalArgumentException(source + " rule missing " + key);
        return value;
    }
    private static void rejectUnknown(JsonNode node, Set<String> allowed, Path source) {
        node.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw new IllegalArgumentException(source + " has unknown field " + field); });
    }
    private static String digest(byte[] data) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
}
