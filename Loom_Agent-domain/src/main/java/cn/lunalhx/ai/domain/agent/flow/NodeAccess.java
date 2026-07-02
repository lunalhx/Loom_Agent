package cn.lunalhx.ai.domain.agent.flow;

import java.util.List;
import java.util.Set;

/**
 * Declares which state partitions a node reads and writes.
 * {@link AgentNode#inputKeys()} derives from {@link #reads()} by default.
 */
public interface NodeAccess {

    NodeAccess NONE = new NodeAccess() {
        @Override public Set<String> reads() { return Set.of(); }
        @Override public Set<String> writes() { return Set.of(); }
    };

    Set<String> reads();

    Set<String> writes();

    default List<String> inputKeys() {
        return List.copyOf(reads());
    }
}
