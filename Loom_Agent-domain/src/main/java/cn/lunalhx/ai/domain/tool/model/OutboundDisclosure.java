package cn.lunalhx.ai.domain.tool.model;

/** Whether a call may disclose data outside the local execution boundary. */
public enum OutboundDisclosure {
    NONE,
    PRESENT,
    UNKNOWN
}
