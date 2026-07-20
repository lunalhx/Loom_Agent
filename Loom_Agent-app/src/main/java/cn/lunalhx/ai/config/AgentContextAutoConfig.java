package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.MemoryStoreProperties;
import cn.lunalhx.ai.domain.agent.service.budget.DefaultBudgetGuard;
import cn.lunalhx.ai.domain.agent.service.context.ContextArtifactPurgeService;
import cn.lunalhx.ai.domain.agent.service.context.ContextWindowManager;
import cn.lunalhx.ai.domain.agent.service.context.DeepContextSummaryService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerCompactionService;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerWatermark;
import cn.lunalhx.ai.domain.agent.service.replay.DefaultReplayService;
import cn.lunalhx.ai.domain.agent.service.replay.ReplayService;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AgentContextAutoConfig {

    @Bean
    public BudgetGuard budgetGuard(AgentRuntimeProperties agent,
                                   ModelRuntimeProperties model,
                                   MemoryStoreProperties memoryStore) {
        return new DefaultBudgetGuard(agent, model, memoryStore);
    }

    @Bean
    public ReplayService replayService(TraceRecorder traceRecorder) {
        return new DefaultReplayService(traceRecorder);
    }

    @Bean
    public DeepContextSummaryService deepContextSummaryService(ModelGateway gateway,
                                                               AgentRuntimeProperties agent,
                                                               BudgetGuard budgetGuard,
                                                               TraceRecorder traceRecorder) {
        return new DeepContextSummaryService(gateway, agent, budgetGuard, traceRecorder);
    }

    @Bean
    public ContextWindowManager contextWindowManager(AgentRuntimeProperties agent,
                                                     ContextArtifactRepository artifacts,
                                                     ContextBlobStore blobs,
                                                     DeepContextSummaryService summaryService) {
        return new ContextWindowManager(agent, artifacts, blobs, summaryService);
    }

    @Bean
    public ConversationLedgerAppendService conversationLedgerAppendService(
            AgentRuntimeProperties properties) {
        return new ConversationLedgerAppendService(properties);
    }

    @Bean
    public LedgerCompactionService ledgerCompactionService(
            AgentRuntimeProperties properties,
            ContextArtifactRepository artifacts,
            ContextBlobStore blobs,
            DeepContextSummaryService summaryService,
            ContextArtifactPurgeService purgeService,
            ContextWindowManager windowManager) {
        var config = properties.getConversationLedger();
        LedgerWatermark watermark = LedgerWatermark.fromConfig(
                config.getCompactionHighWatermark(), config.getCompactionLowWatermark());
        return new LedgerCompactionService(watermark, artifacts, blobs, summaryService, purgeService,
                config.getMaxCompactionDepth(), windowManager::estimateTokens,
                properties.getContext().getAutoCompactTokenLimit());
    }
}
