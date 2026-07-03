package cn.lunalhx.ai.domain.agent.service.prompt;

import cn.lunalhx.ai.domain.agent.service.ledger.LedgerBootstrapService;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerCompactionService;

import java.util.Objects;

/**
 * Mandatory ledger services consumed by {@code RenderPromptNode}.
 */
public final class LedgerPromptServices {

    private final LedgerBootstrapService bootstrapService;
    private final StablePrefixBuilder prefixBuilder;
    private final LedgerCompactionService compactionService;

    public LedgerPromptServices(LedgerBootstrapService bootstrapService,
                                 StablePrefixBuilder prefixBuilder,
                                 LedgerCompactionService compactionService) {
        this.bootstrapService = Objects.requireNonNull(bootstrapService, "bootstrapService must not be null");
        this.prefixBuilder = Objects.requireNonNull(prefixBuilder, "prefixBuilder must not be null");
        this.compactionService = Objects.requireNonNull(compactionService, "compactionService must not be null");
    }

    public LedgerBootstrapService bootstrapService() { return bootstrapService; }
    public StablePrefixBuilder prefixBuilder() { return prefixBuilder; }
    public LedgerCompactionService compactionService() { return compactionService; }
}
