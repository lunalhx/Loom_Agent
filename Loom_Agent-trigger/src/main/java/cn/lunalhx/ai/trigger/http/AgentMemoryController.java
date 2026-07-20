package cn.lunalhx.ai.trigger.http;

import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.memory.model.entity.AgentMemory;
import cn.lunalhx.ai.domain.memory.model.valobj.MemorySourceType;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryStatus;
import cn.lunalhx.ai.domain.memory.model.valobj.MemoryType;
import cn.lunalhx.ai.types.error.ApplicationException;
import cn.lunalhx.ai.types.error.ErrorCategory;
import cn.lunalhx.ai.types.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/agent")
@ConditionalOnProperty(name = "loom.agent.long-term-memory.enabled", havingValue = "true")
public class AgentMemoryController {

    private final AgentMemoryRepository memoryRepository;

    private static final ErrorCode MEMORY_NOT_FOUND = new ErrorCode() {
        @Override public String code() { return "memory_not_found"; }
        @Override public String defaultMessage() { return "记忆不存在"; }
        @Override public ErrorCategory category() { return ErrorCategory.NOT_FOUND; }
    };

    private static final ErrorCode MEMORY_DELETED = new ErrorCode() {
        @Override public String code() { return "memory_gone"; }
        @Override public String defaultMessage() { return "记忆已删除"; }
        @Override public ErrorCategory category() { return ErrorCategory.GONE; }
    };

    @GetMapping("/memories")
    public Response<List<AgentMemory>> listMemories(
            @RequestParam String workspacePath,
            @RequestParam(defaultValue = "100") int limit) {
        String workspaceKey = cn.lunalhx.ai.domain.memory.service.WorkspaceKeyUtil.compute(workspacePath);
        List<AgentMemory> memories = memoryRepository.findActive(workspaceKey, Math.min(limit, 200));
        return Response.success(memories);
    }

    @GetMapping("/memories/{memoryId}")
    public Response<AgentMemory> getMemory(@PathVariable String memoryId) {
        return memoryRepository.findById(memoryId)
                .filter(m -> m.getStatus() != MemoryStatus.DELETED)
                .map(m -> Response.success(m))
                .orElseThrow(() -> new ApplicationException(MEMORY_NOT_FOUND));
    }

    @PostMapping("/memories")
    public Response<AgentMemory> createMemory(@RequestBody Map<String, Object> body) {
        String workspacePath = (String) body.getOrDefault("workspacePath", "");
        String workspaceKey = cn.lunalhx.ai.domain.memory.service.WorkspaceKeyUtil.compute(workspacePath);
        MemoryType type = MemoryType.valueOf((String) body.get("type"));
        String title = (String) body.get("title");

        if (title == null || title.isBlank()) {
            throw new ApplicationException(
                    cn.lunalhx.ai.domain.common.CommonErrorCode.INVALID_PARAMETER, "title 不能为空");
        }

        AgentMemory memory = AgentMemory.builder()
                .memoryId(UUID.randomUUID().toString())
                .workspaceKey(workspaceKey)
                .workspacePath(workspacePath)
                .type(type)
                .title(title)
                .summary((String) body.getOrDefault("summary", ""))
                .body((String) body.getOrDefault("body", ""))
                .status(MemoryStatus.ACTIVE)
                .pinned(Boolean.TRUE.equals(body.get("pinned")))
                .importance(body.get("importance") instanceof Number n ? n.intValue() : 50)
                .sourceType(MemorySourceType.MANUAL_API)
                .contentHash("")
                .version(0)
                .usageCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        return Response.success(memoryRepository.save(memory));
    }

    @PutMapping("/memories/{memoryId}")
    public Response<AgentMemory> updateMemory(@PathVariable String memoryId,
                                               @RequestBody Map<String, Object> body) {
        AgentMemory existing = memoryRepository.findById(memoryId)
                .orElseThrow(() -> new ApplicationException(MEMORY_NOT_FOUND));

        if (existing.getStatus() == MemoryStatus.DELETED) {
            throw new ApplicationException(MEMORY_DELETED);
        }

        MemoryType type = body.containsKey("type") ? MemoryType.valueOf((String) body.get("type")) : existing.getType();
        String title = body.containsKey("title") ? (String) body.get("title") : existing.getTitle();
        String summary = body.containsKey("summary") ? (String) body.get("summary") : existing.getSummary();
        String bodyText = body.containsKey("body") ? (String) body.get("body") : existing.getBody();
        boolean pinned = body.containsKey("pinned") ? Boolean.TRUE.equals(body.get("pinned")) : existing.isPinned();
        int importance = body.get("importance") instanceof Number n ? n.intValue() : existing.getImportance();

        AgentMemory updated = AgentMemory.builder()
                .memoryId(memoryId)
                .workspaceKey(existing.getWorkspaceKey())
                .workspacePath(existing.getWorkspacePath())
                .type(type)
                .title(title)
                .summary(summary)
                .body(bodyText)
                .status(existing.getStatus())
                .pinned(pinned)
                .importance(importance)
                .sourceType(MemorySourceType.MANUAL_API)
                .contentHash(existing.getContentHash())
                .version(existing.getVersion() + 1)
                .usageCount(existing.getUsageCount())
                .lastUsedAt(existing.getLastUsedAt())
                .createdAt(existing.getCreatedAt())
                .updatedAt(Instant.now())
                .build();

        return Response.success(memoryRepository.save(updated));
    }

    @DeleteMapping("/memories/{memoryId}")
    public Response<Void> deleteMemory(@PathVariable String memoryId) {
        AgentMemory existing = memoryRepository.findById(memoryId)
                .orElseThrow(() -> new ApplicationException(MEMORY_NOT_FOUND));

        memoryRepository.updateStatus(memoryId, MemoryStatus.DELETED);
        return Response.success(null);
    }
}
