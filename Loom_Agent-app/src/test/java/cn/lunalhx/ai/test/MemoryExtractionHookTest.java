package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookContext;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookEvent;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryGenerationJobRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentMemoryGenerationJobRepository;
import cn.lunalhx.ai.runtime.hook.MemoryExtractionHook;
import cn.lunalhx.ai.config.MemoryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;

import static org.junit.Assert.*;

public class MemoryExtractionHookTest {

    private AgentMemoryGenerationJobRepository jobRepo;
    private MemoryProperties properties;
    private ObjectMapper objectMapper;
    private MemoryExtractionHook hook;

    @Before
    public void setUp() {
        jobRepo = new InMemoryAgentMemoryGenerationJobRepository();
        objectMapper = new ObjectMapper();

        properties = new MemoryProperties();
        properties.setEnabled(true);
        properties.setGenerateMemories(true);
        properties.setGenerationDelayMinutes(0);

        hook = new MemoryExtractionHook(jobRepo, properties, objectMapper);
    }

    @Test
    public void shouldCreateJobOnAfterStop() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-hook-1");
        ctx.setQuestion("What is Java?");
        ctx.setFinalAnswer("Java is a programming language.");
        ctx.setStep(5);
        ctx.setResolvedWorkspace(Path.of("/tmp/test"));

        AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();

        AgentHookResult result = hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);
        assertTrue("Result should not be null", result != null);

        var job = jobRepo.findBySourceRunId("run-hook-1");
        assertTrue("Job should be created", job.isPresent());
        assertTrue("Payload should contain question",
                job.get().getConversationSummaryJson().contains("What is Java?"));
        assertTrue("Payload should contain workspacePath",
                job.get().getConversationSummaryJson().contains("workspacePath"));
    }

    @Test
    public void shouldNotCreateJobForNonAfterStopEvents() {
        for (AgentHookEvent event : AgentHookEvent.values()) {
            if (event == AgentHookEvent.AFTER_STOP) continue;

            AgentContext ctx = new AgentContext();
            ctx.setRunId("run-" + event.name());
            ctx.setFinalAnswer("Some answer");

            AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();
            hook.onEvent(event, hookCtx);

            assertFalse("Should not create job for " + event,
                    jobRepo.findBySourceRunId("run-" + event.name()).isPresent());
        }
    }

    @Test
    public void shouldNotCreateJobForSubAgent() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-sub");
        ctx.setParentRunId("parent-run");
        ctx.setFinalAnswer("Sub agent answer");

        AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();
        hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);

        assertFalse(jobRepo.findBySourceRunId("run-sub").isPresent());
    }

    @Test
    public void shouldNotCreateJobForEmptyFinalAnswer() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-empty");
        ctx.setFinalAnswer("");

        AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();
        hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);

        assertFalse(jobRepo.findBySourceRunId("run-empty").isPresent());
    }

    @Test
    public void shouldNotCreateJobForNullFinalAnswer() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-null");
        ctx.setFinalAnswer(null);

        AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();
        hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);

        assertFalse(jobRepo.findBySourceRunId("run-null").isPresent());
    }

    @Test
    public void shouldNotCreateJobWhenDisabled() {
        properties.setEnabled(false);

        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-off");
        ctx.setFinalAnswer("answer");

        AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();
        hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);

        assertFalse(jobRepo.findBySourceRunId("run-off").isPresent());
    }

    @Test
    public void shouldNotCreateJobWhenGenerateMemoriesDisabled() {
        properties.setGenerateMemories(false);

        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-no-gen");
        ctx.setFinalAnswer("answer");

        AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();
        hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);

        assertFalse(jobRepo.findBySourceRunId("run-no-gen").isPresent());
    }

    @Test
    public void shouldDeduplicateJobsForSameSourceRunId() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-once");
        ctx.setQuestion("q");
        ctx.setFinalAnswer("a");
        ctx.setStep(1);
        ctx.setResolvedWorkspace(Path.of("/tmp"));

        AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();

        hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);
        hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);

        var job = jobRepo.findBySourceRunId("run-once");
        assertTrue(job.isPresent());
    }

    @Test
    public void shouldSetNotBeforeBasedOnDelay() {
        properties.setGenerationDelayMinutes(5);

        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-delay");
        ctx.setQuestion("q");
        ctx.setFinalAnswer("a");
        ctx.setStep(1);
        ctx.setResolvedWorkspace(Path.of("/tmp"));

        AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();
        hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);

        var job = jobRepo.findBySourceRunId("run-delay");
        assertTrue(job.isPresent());
    }

    @Test
    public void shouldHandleNullContext() {
        AgentHookContext hookCtx = AgentHookContext.builder().build();
        AgentHookResult result = hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);
        assertTrue("Result should not be null", result != null);
    }

    @Test
    public void shouldHandleSpecialCharactersInPayload() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("run-special");
        ctx.setQuestion("What about \"quotes\" and\nnewlines and \\backslashes?");
        ctx.setFinalAnswer("Answer with \"nested\" JSON-like content\nand\r\nmultiple lines.");
        ctx.setStep(10);
        ctx.setResolvedWorkspace(Path.of("/tmp/test dir"));

        AgentHookContext hookCtx = AgentHookContext.builder().agentContext(ctx).build();
        hook.onEvent(AgentHookEvent.AFTER_STOP, hookCtx);

        var job = jobRepo.findBySourceRunId("run-special");
        assertTrue(job.isPresent());

        String json = job.get().getConversationSummaryJson();
        assertTrue(json.contains("quotes"));
        assertTrue(json.contains("backslashes"));
        assertTrue(json.contains("test dir"));
    }
}
