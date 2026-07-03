package cn.lunalhx.ai.domain.agent.service.prompt;

import cn.lunalhx.ai.domain.agent.service.ledger.LedgerBootstrapService;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerCompactionService;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerShadowDiagnostic;

/**
 * Ledger services consumed by {@code RenderPromptNode}: shadow diagnostic,
 * bootstrap, prefix builder, and compaction.
 *
 * <p>Wraps four dependencies into a single parameter. When ledger is
 * disabled, all fields are {@code null} and the node safely no-ops.
 */
public final class LedgerPromptServices {

    private final LedgerShadowDiagnostic shadowDiagnostic;
    private final LedgerBootstrapService bootstrapService;
    private final StablePrefixBuilder prefixBuilder;
    private final LedgerCompactionService compactionService;

    public LedgerPromptServices(LedgerShadowDiagnostic shadowDiagnostic,
                                 LedgerBootstrapService bootstrapService,
                                 StablePrefixBuilder prefixBuilder,
                                 LedgerCompactionService compactionService) {
        this.shadowDiagnostic = shadowDiagnostic;
        this.bootstrapService = bootstrapService;
        this.prefixBuilder = prefixBuilder != null ? prefixBuilder : new StablePrefixBuilder();
        this.compactionService = compactionService;
    }

    /** Factory for when ledger is disabled — all fields null/neutral. */
    public static LedgerPromptServices disabled() {
        return new LedgerPromptServices(null, null, new StablePrefixBuilder(), null);
    }

    /** Factory with only a prefix builder (for bootstrap-capable but not compaction-ready setups). */
    public static LedgerPromptServices withBootstrap(LedgerBootstrapService bootstrapService,
                                                      StablePrefixBuilder prefixBuilder) {
        return new LedgerPromptServices(null, bootstrapService, prefixBuilder, null);
    }

    public LedgerShadowDiagnostic shadowDiagnostic() { return shadowDiagnostic; }
    public LedgerBootstrapService bootstrapService() { return bootstrapService; }
    public StablePrefixBuilder prefixBuilder() { return prefixBuilder; }
    public LedgerCompactionService compactionService() { return compactionService; }
}
