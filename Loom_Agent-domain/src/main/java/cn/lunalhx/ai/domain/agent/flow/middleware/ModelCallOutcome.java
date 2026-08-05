package cn.lunalhx.ai.domain.agent.flow.middleware;

import cn.lunalhx.ai.domain.agent.flow.NodeResult;

import java.util.Collections;

public class ModelCallOutcome {

    public enum Type { SUCCESS, BUDGET_BLOCKED, TRUNCATION_EXHAUSTED, ERROR, ROUTED }

    private final Type type;
    private final NodeResult route;
    private final String errorMessage;

    private ModelCallOutcome(Type type, NodeResult route, String errorMessage) {
        this.type = type;
        this.route = route;
        this.errorMessage = errorMessage;
    }

    public static ModelCallOutcome success() {
        return new ModelCallOutcome(Type.SUCCESS, null, null);
    }

    public static ModelCallOutcome budgetBlocked() {
        return new ModelCallOutcome(Type.BUDGET_BLOCKED,
                NodeResult.fail(Collections.emptyList()), null);
    }

    public static ModelCallOutcome truncationExhausted() {
        return new ModelCallOutcome(Type.TRUNCATION_EXHAUSTED,
                NodeResult.fail(Collections.emptyList()), null);
    }

    public static ModelCallOutcome error(String message) {
        return new ModelCallOutcome(Type.ERROR,
                NodeResult.fail(Collections.emptyList()), message);
    }

    public static ModelCallOutcome routed(NodeResult route) {
        return new ModelCallOutcome(Type.ROUTED, route, null);
    }

    public Type type() { return type; }
    public NodeResult route() { return route; }
    public String errorMessage() { return errorMessage; }
}