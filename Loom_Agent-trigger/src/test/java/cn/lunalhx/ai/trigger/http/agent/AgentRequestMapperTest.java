package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.api.dto.AgentApprovalDecisionRequest;
import cn.lunalhx.ai.api.dto.AgentAskRequest;
import cn.lunalhx.ai.api.dto.AgentReplayStreamRequest;
import cn.lunalhx.ai.api.dto.AgentUserInputRequest;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.UserInputAction;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AgentRequestMapperTest {

    private AgentRequestMapper mapper;
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Before
    public void setUp() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setEnabled(true);
        mapper = new AgentRequestMapper(properties, validator);
    }

    // ===== mapAsk =====

    @Test
    public void mapAskNullBodyShouldReturnInvalidRequest() {
        AgentRequestMapper.Result<?> result = mapper.mapAsk(null);
        assertFalse(result.valid());
        assertEquals("invalid_request", result.problem().code());
        assertEquals("请求体不能为空", result.problem().message());
    }

    @Test
    public void mapAskAgentDisabledShouldReturnAgentDisabled() {
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        props.setEnabled(false);
        AgentRequestMapper disabledMapper = new AgentRequestMapper(props, validator);
        AgentRequestMapper.Result<?> result = disabledMapper.mapAsk(new AgentAskRequest());
        assertFalse(result.valid());
        assertEquals("agent_disabled", result.problem().code());
        assertEquals("Agent 功能未启用", result.problem().message());
    }

    @Test
    public void mapAskQuestionAndMessageBothBlankShouldReturnInvalidRequest() {
        AgentAskRequest request = new AgentAskRequest();
        request.setQuestion("");
        request.setMessage(null);
        AgentRequestMapper.Result<?> result = mapper.mapAsk(request);
        assertFalse(result.valid());
        assertEquals("invalid_request", result.problem().code());
        assertEquals("question 不能为空", result.problem().message());
    }

    @Test
    public void mapAskQuestionShouldTakePriority() {
        AgentAskRequest request = new AgentAskRequest();
        request.setQuestion("what is this?");
        request.setMessage("fallback message");
        AgentRequestMapper.Result<?> result = mapper.mapAsk(request);
        assertTrue(result.valid());
        assertEquals("what is this?", ((cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion) result.value()).getQuestion());
    }

    @Test
    public void mapAskMessageFallbackWhenQuestionNull() {
        AgentAskRequest request = new AgentAskRequest();
        request.setQuestion(null);
        request.setMessage("message fallback");
        AgentRequestMapper.Result<?> result = mapper.mapAsk(request);
        assertTrue(result.valid());
        assertEquals("message fallback", ((cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion) result.value()).getQuestion());
    }

    @Test
    public void mapAskMessageFallbackWhenQuestionBlank() {
        AgentAskRequest request = new AgentAskRequest();
        request.setQuestion("  ");
        request.setMessage("message fallback");
        AgentRequestMapper.Result<?> result = mapper.mapAsk(request);
        assertTrue(result.valid());
        assertEquals("message fallback", ((cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion) result.value()).getQuestion());
    }

    @Test
    public void mapAskShouldGenerateRequestId() {
        AgentAskRequest request = new AgentAskRequest();
        request.setQuestion("hi");
        AgentRequestMapper.Result<?> result = mapper.mapAsk(request);
        assertTrue(result.valid());
        assertNotNull(((cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion) result.value()).getRequestId());
    }

    @Test
    public void mapAskShouldPreserveWorkspaceMaxStepsIncludeTrace() {
        AgentAskRequest request = new AgentAskRequest();
        request.setQuestion("hi");
        request.setWorkspace("/tmp");
        request.setMaxSteps(10);
        request.setIncludeTrace(true);
        AgentRequestMapper.Result<?> result = mapper.mapAsk(request);
        assertTrue(result.valid());
        cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion q = (cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion) result.value();
        assertEquals("/tmp", q.getWorkspace());
        assertEquals(Integer.valueOf(10), q.getMaxSteps());
        assertTrue(q.getIncludeTrace());
    }

    @Test
    public void mapAskBeanValidationFailureShouldReturnInvalidRequest() {
        AgentAskRequest request = new AgentAskRequest();
        request.setQuestion("hi");
        request.setMaxSteps(100); // exceeds max 30
        AgentRequestMapper.Result<?> result = mapper.mapAsk(request);
        assertFalse(result.valid());
        assertEquals("invalid_request", result.problem().code());
    }

    // ===== mapApproval =====

    @Test
    public void mapApprovalNullBodyShouldReturnInvalidRequest() {
        AgentRequestMapper.Result<?> result = mapper.mapApproval(null);
        assertFalse(result.valid());
        assertEquals("invalid_request", result.problem().code());
        assertEquals("请求体不能为空", result.problem().message());
    }

    @Test
    public void mapApprovalAgentDisabledShouldReturnAgentDisabled() {
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        props.setEnabled(false);
        AgentRequestMapper disabledMapper = new AgentRequestMapper(props, validator);
        AgentRequestMapper.Result<?> result = disabledMapper.mapApproval(new AgentApprovalDecisionRequest("APPROVE", null));
        assertFalse(result.valid());
        assertEquals("agent_disabled", result.problem().code());
    }

    @Test
    public void mapApprovalApproveShouldMapCorrectly() {
        AgentRequestMapper.Result<?> result = mapper.mapApproval(new AgentApprovalDecisionRequest("APPROVE", "ok"));
        assertTrue(result.valid());
        AgentRequestMapper.ApprovalCommand cmd = (AgentRequestMapper.ApprovalCommand) result.value();
        assertEquals(ApprovalDecision.APPROVE, cmd.decision());
        assertEquals("ok", cmd.reason());
    }

    @Test
    public void mapApprovalRejectShouldMapCorrectly() {
        AgentRequestMapper.Result<?> result = mapper.mapApproval(new AgentApprovalDecisionRequest("REJECT", null));
        assertTrue(result.valid());
        AgentRequestMapper.ApprovalCommand cmd = (AgentRequestMapper.ApprovalCommand) result.value();
        assertEquals(ApprovalDecision.REJECT, cmd.decision());
        assertNull(cmd.reason());
    }

    @Test
    public void mapApprovalCaseInsensitiveAndTrimmed() {
        AgentRequestMapper.Result<?> result = mapper.mapApproval(new AgentApprovalDecisionRequest("  approve  ", null));
        assertTrue(result.valid());
        AgentRequestMapper.ApprovalCommand cmd = (AgentRequestMapper.ApprovalCommand) result.value();
        assertEquals(ApprovalDecision.APPROVE, cmd.decision());
    }

    @Test
    public void mapApprovalInvalidDecisionShouldReturnInvalidRequest() {
        AgentRequestMapper.Result<?> result = mapper.mapApproval(new AgentApprovalDecisionRequest("MAYBE", null));
        assertFalse(result.valid());
        assertEquals("invalid_request", result.problem().code());
        assertTrue(result.problem().message().contains("APPROVE"));
    }

    @Test
    public void mapApprovalBeanValidationFailureShouldReturnInvalidRequest() {
        AgentRequestMapper.Result<?> result = mapper.mapApproval(new AgentApprovalDecisionRequest("", null)); // blank
        assertFalse(result.valid());
        assertEquals("invalid_request", result.problem().code());
    }

    // ===== mapRunId =====

    @Test
    public void mapRunIdBlankShouldReturnInvalidRequest() {
        AgentRequestMapper.Result<?> result = mapper.mapRunId(null);
        assertFalse(result.valid());
        assertEquals("invalid_request", result.problem().code());
        assertEquals("runId 不能为空", result.problem().message());
    }

    @Test
    public void mapRunIdEmptyShouldReturnInvalidRequest() {
        AgentRequestMapper.Result<?> result = mapper.mapRunId("  ");
        assertFalse(result.valid());
        assertEquals("invalid_request", result.problem().code());
    }

    @Test
    public void mapRunIdValidShouldReturnSuccess() {
        AgentRequestMapper.Result<?> result = mapper.mapRunId("r-1");
        assertTrue(result.valid());
        assertEquals("r-1", result.value());
    }

    // ===== mapUserInput =====

    @Test
    public void mapUserInputBlankRunIdShouldReturnInvalidRequest() {
        AgentRequestMapper.Result<?> result = mapper.mapUserInput(null, new AgentUserInputRequest("CONTINUE", "msg"));
        assertFalse(result.valid());
        assertEquals("invalid_request", result.problem().code());
        assertEquals("runId 不能为空", result.problem().message());
    }

    @Test
    public void mapUserInputNullBodyShouldReturnInvalidRequest() {
        AgentRequestMapper.Result<?> result = mapper.mapUserInput("r-1", null);
        assertFalse(result.valid());
        assertEquals("invalid_request", result.problem().code());
        assertEquals("请求体不能为空", result.problem().message());
    }

    @Test
    public void mapUserInputAgentDisabledShouldReturnAgentDisabled() {
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        props.setEnabled(false);
        AgentRequestMapper disabledMapper = new AgentRequestMapper(props, validator);
        AgentRequestMapper.Result<?> result = disabledMapper.mapUserInput("r-1", new AgentUserInputRequest("CONTINUE", "msg"));
        assertFalse(result.valid());
        assertEquals("agent_disabled", result.problem().code());
    }

    @Test
    public void mapUserInputContinueShouldMapCorrectly() {
        AgentRequestMapper.Result<?> result = mapper.mapUserInput("r-1", new AgentUserInputRequest("CONTINUE", "more"));
        assertTrue(result.valid());
        AgentRequestMapper.UserInputCommand cmd = (AgentRequestMapper.UserInputCommand) result.value();
        assertEquals(UserInputAction.CONTINUE, cmd.action());
        assertEquals("more", cmd.message());
    }

    @Test
    public void mapUserInputAbortShouldMapCorrectly() {
        AgentRequestMapper.Result<?> result = mapper.mapUserInput("r-1", new AgentUserInputRequest("ABORT", null));
        assertTrue(result.valid());
        AgentRequestMapper.UserInputCommand cmd = (AgentRequestMapper.UserInputCommand) result.value();
        assertEquals(UserInputAction.ABORT, cmd.action());
        assertNull(cmd.message());
    }

    @Test
    public void mapUserInputInvalidActionShouldReturnInvalidRequest() {
        AgentRequestMapper.Result<?> result = mapper.mapUserInput("r-1", new AgentUserInputRequest("BOGUS", null));
        assertFalse(result.valid());
        assertEquals("invalid_request", result.problem().code());
        assertTrue(result.problem().message().contains("CONTINUE"));
    }

    @Test
    public void mapUserInputContinueMissingMessageShouldReturnInvalidRequest() {
        AgentRequestMapper.Result<?> result = mapper.mapUserInput("r-1", new AgentUserInputRequest("CONTINUE", null));
        assertFalse(result.valid());
        assertEquals("invalid_request", result.problem().code());
        assertEquals("CONTINUE 必须提供非空 message", result.problem().message());
    }

    @Test
    public void mapUserInputContinueEmptyMessageShouldReturnInvalidRequest() {
        AgentRequestMapper.Result<?> result = mapper.mapUserInput("r-1", new AgentUserInputRequest("CONTINUE", "  "));
        assertFalse(result.valid());
        assertEquals("invalid_request", result.problem().code());
        assertEquals("CONTINUE 必须提供非空 message", result.problem().message());
    }

    @Test
    public void mapUserInputCaseInsensitiveAndTrimmed() {
        AgentRequestMapper.Result<?> result = mapper.mapUserInput("r-1", new AgentUserInputRequest("  continue  ", "msg"));
        assertTrue(result.valid());
        AgentRequestMapper.UserInputCommand cmd = (AgentRequestMapper.UserInputCommand) result.value();
        assertEquals(UserInputAction.CONTINUE, cmd.action());
    }

    // ===== resolveIncludeChildren =====

    @Test
    public void includeChildrenQueryParamShouldTakePriority() {
        assertFalse(mapper.resolveIncludeChildren(false, new AgentReplayStreamRequest(true)));
        assertTrue(mapper.resolveIncludeChildren(true, new AgentReplayStreamRequest(false)));
    }

    @Test
    public void includeChildrenBodyFallback() {
        assertFalse(mapper.resolveIncludeChildren(null, new AgentReplayStreamRequest(false)));
        assertTrue(mapper.resolveIncludeChildren(null, new AgentReplayStreamRequest(true)));
    }

    @Test
    public void includeChildrenDefaultsToTrue() {
        assertTrue(mapper.resolveIncludeChildren(null, null));
        assertTrue(mapper.resolveIncludeChildren(null, new AgentReplayStreamRequest()));
    }
}
