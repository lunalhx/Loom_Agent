package cn.lunalhx.ai.domain.agent.service.prompt;

import cn.lunalhx.ai.domain.agent.service.ledger.LedgerBootstrapService;

import java.util.Objects;

/**
 * Mandatory ledger services consumed by {@code RenderPromptNode}.
 */
public final class LedgerPromptServices {

    private final LedgerBootstrapService bootstrapService;
    private final StablePrefixBuilder prefixBuilder;

    public LedgerPromptServices(LedgerBootstrapService bootstrapService,
                                 StablePrefixBuilder prefixBuilder) {
        this.bootstrapService = Objects.requireNonNull(bootstrapService, "bootstrapService must not be null");
        this.prefixBuilder = Objects.requireNonNull(prefixBuilder, "prefixBuilder must not be null");
    }

    public LedgerBootstrapService bootstrapService() { return bootstrapService; }
    public StablePrefixBuilder prefixBuilder() { return prefixBuilder; }
}
