package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.Application;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.infrastructure.store.FileAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentRunRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentSessionRepository;
import cn.lunalhx.ai.infrastructure.store.FileTraceRecorder;
import org.junit.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CliRuntimeCompositionTest {

    @Test
    public void springCliUsesOneFileBackedRuntimeComposition() throws Exception {
        Path workspace = Files.createTempDirectory("spring-cli-composition");
        SpringApplication application = new SpringApplication(Application.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        application.setBannerMode(org.springframework.boot.Banner.Mode.OFF);

        try (ConfigurableApplicationContext context = application.run(
                "--loom.agent.workspace-root=" + workspace,
                "--loom.agent.allowed-workspace-roots=" + workspace,
                "--loom.agent.approval-policy=never",
                "--loom.ai.provider=deepseek",
                "--loom.ai.allowed-models=deepseek-v4-flash",
                "--loom.ai.providers.deepseek.base-url=http://unused",
                "--loom.ai.providers.deepseek.api-key=",
                "--loom.ai.providers.deepseek.default-model=deepseek-v4-flash")) {
            assertEquals(1, context.getBeansOfType(AgentLoopService.class).size());
            assertEquals(1, context.getBeansOfType(ToolRegistry.class).size());
            assertTrue(context.getBean(AgentSessionRepository.class)
                    instanceof FileAgentSessionRepository);
            assertTrue(context.getBean(AgentRunRepository.class)
                    instanceof FileAgentRunRepository);
            assertTrue(context.getBean(AgentCheckpointRepository.class)
                    instanceof FileAgentCheckpointRepository);
            assertTrue(context.getBean(TraceRecorder.class)
                    instanceof FileTraceRecorder);

            CliSessionService.CliOptions options = new CliSessionService.CliOptions();
            options.provider = "deepseek";
            options.model = "deepseek-v4-flash";
            options.baseUrl = "http://unused";
            options.apiKey = "";
            options.workspaceRoot = workspace.toString();
            options.approvalPolicy = "never";
            options.modelGateway = null;
            CliSessionService service = new CliSessionService(context, options);
            assertSame(context.getBean(AgentSessionRepository.class), service.sessionRepository());
            assertSame(context.getBean(AgentRunRepository.class), service.runRepository());
            service.close();
            assertTrue(Files.isRegularFile(service.sessionPath()));
        }
    }
}
