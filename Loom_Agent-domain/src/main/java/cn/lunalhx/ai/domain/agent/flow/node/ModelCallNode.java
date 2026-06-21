package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.OutputFormat;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.util.List;

public class ModelCallNode extends AbstractAgentNode {

    private final ModelGateway modelGateway;
    private final AgentRuntimeProperties properties;

    public ModelCallNode(ModelGateway modelGateway, AgentRuntimeProperties properties) {
        super(AgentNodeNames.MODEL_CALL, List.of("currentPrompt", "requestId", "conversationId"));
        this.modelGateway = modelGateway;
        this.properties = properties;
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        try {
            ChatPrompt prompt = ChatPrompt.builder()
                    .requestId(context.getRequestId())
                    .conversationId(context.getConversationId())
                    .message(context.getCurrentPrompt())
                    .outputFormat(OutputFormat.JSON_OBJECT)
                    .build();
            ModelChatResult result = modelGateway.complete(prompt)
                    .timeout(Duration.ofMillis(properties.getStepTimeoutMs()))
                    .block(Duration.ofMillis(properties.getStepTimeoutMs() + 1000L));
            if (result == null || StringUtils.isBlank(result.getContent())) {
                throw new IllegalStateException("模型响应为空");
            }
            context.setModelOutput(result.getContent());
            return NodeResult.next(AgentNodeNames.DECISION, List.of());
        } catch (Exception e) {
            fail(context, AgentStopReason.MODEL_ERROR, "model_error", "模型决策失败");
            return NodeResult.next(AgentNodeNames.FAIL, List.of());
        }
    }

}
