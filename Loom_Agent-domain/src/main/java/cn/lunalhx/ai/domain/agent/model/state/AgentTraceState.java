package cn.lunalhx.ai.domain.agent.model.state;

/**
 * Mutable trace state: trace/span identity and sequence counter.
 */
public final class AgentTraceState {

    private String traceId;
    private String currentSpanId;
    private String parentSpanId;
    private long traceSequenceNo;

    // -- getters --

    public String traceId() { return traceId; }
    public String currentSpanId() { return currentSpanId; }
    public String parentSpanId() { return parentSpanId; }
    public long traceSequenceNo() { return traceSequenceNo; }

    // -- package-private mutators --

    public void setTraceId(String v) { this.traceId = v; }
    public void setCurrentSpanId(String v) { this.currentSpanId = v; }
    public void setParentSpanId(String v) { this.parentSpanId = v; }
    public void setTraceSequenceNo(long v) { this.traceSequenceNo = v; }

    // -- behavior methods --

    public long nextSequenceNo() {
        traceSequenceNo++;
        return traceSequenceNo;
    }
}
