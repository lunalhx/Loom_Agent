package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryInitializer;
import java.util.List;

final class FormatReminderStep implements ContextOverflowStep {

    private static final String FORMAT_REMINDER =
            "【格式提醒】你的上一次响应无法解析。请确保返回合法的 JSON，" +
            "必须包含 \"type\" 字段，值为 \"action\" 或 \"final\"。" +
            "如果是 action，必须包含 \"tool\" 和 \"args\" 字段。" +
            "如果是 final，必须包含 \"answer\" 字段。";

    private final ConversationHistoryAppendService ledgerAppendService;

    FormatReminderStep() {
        this(null);
    }

    FormatReminderStep(ConversationHistoryAppendService ledgerAppendService) {
        this.ledgerAppendService = ledgerAppendService;
    }

    @Override
    public ContextOverflowTransition apply(ContextOverflowRequest request, List<AgentEvent> accumulatedEvents) {
        AgentContext context = request.context();
        if (ledgerAppendService != null) {
            ledgerAppendService.appendSystemNote(context, FORMAT_REMINDER,
                    ConversationHistoryInitializer.eventKey(context.getRunId(),
                            String.valueOf(Math.max(1, context.getToolSteps())), "format_reminder"));
        }
        AgentEvent event = AgentEvent.builder()
                .type(AgentEventType.OBSERVATION)
                .code("format_reminder_injected")
                .message("已注入格式提醒，重试模型调用")
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .parentRunId(context.getParentRunId())
                .build();
        accumulatedEvents.add(event);
        return ContextOverflowTransition.renderPrompt(accumulatedEvents);
    }
}
