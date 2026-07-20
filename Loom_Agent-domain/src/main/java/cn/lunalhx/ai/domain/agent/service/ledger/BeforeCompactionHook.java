package cn.lunalhx.ai.domain.agent.service.ledger;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedgerEntry;

import java.util.List;

@FunctionalInterface
public interface BeforeCompactionHook {

    void beforeCompaction(AgentContext context,
                          List<ConversationLedgerEntry> entriesToCompact,
                          List<ConversationLedgerEntry> preservedEntries);
}
