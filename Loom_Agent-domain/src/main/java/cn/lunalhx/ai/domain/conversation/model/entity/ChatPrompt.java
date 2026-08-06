package cn.lunalhx.ai.domain.conversation.model.entity;

import cn.lunalhx.ai.domain.model.valobj.OutputFormat;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.PromptCacheCapability;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatPrompt {

    private String requestId;
    private String conversationId;
    private String message;
    private String systemPrompt;
    private String model;
    private Double temperature;
    private Integer maxTokens;
    private OutputFormat outputFormat;
    private String capability;
    private ModelCallPurpose purpose;
    private Long deadlineEpochMs;
    private List<ChatMessage> messages;
    private ModelRuntimeProperties runtimeProperties;

    /** Deterministic signature of the stable prefix portion (tool catalog +
     *  workspace identity + role protocol). Only this is allowed in a cache key. */
    private String stablePrefixSignature;

    /** Provider cache policy: NONE / READ / READ_WRITE. */
    private CachePolicy cachePolicy = CachePolicy.READ;

    /** SHA-256 prompt-cache key derived from the stable prefix (null when disabled). */
    private String promptCacheKey;

    /** Optional provider cache retention hint. */
    private String promptCacheRetention;

    /** Provider cache capability snapshot used by the transport. */
    private PromptCacheCapability promptCacheCapability = PromptCacheCapability.UNSUPPORTED;

    public enum CachePolicy {
        NONE, READ, READ_WRITE
    }
}
