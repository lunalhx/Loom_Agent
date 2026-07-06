package cn.lunalhx.ai.domain.agent.flow.node;

/**
 * Error codes for {@link DecisionParseException}.
 */
enum DecisionParseErrorCode {
    EMPTY_OUTPUT,
    INVALID_JSON,
    MISSING_TYPE,
    INVALID_TYPE,
    MISSING_TOOL,
    NON_OBJECT_INPUT
}
