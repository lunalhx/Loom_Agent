package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
public class AgentContext {

    private String requestId;
    private String conversationId;
    private String question;
    private int maxSteps;
    private int step;
    private int parseErrors;
    private Instant startedAt;
    private List<ToolSpec> toolSpecs = new ArrayList<>();
    private List<AgentStep> history = new ArrayList<>();
    private String currentPrompt;
    private String modelOutput;
    private AgentDecision decision;
    private ToolResult toolResult;
    private String finalAnswer;
    private AgentStopReason stopReason;
    private String errorCode;
    private String errorMessage;

}
