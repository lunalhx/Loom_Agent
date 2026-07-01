package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.adapter.port.SkillRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.context.ContextRecallTool;
import cn.lunalhx.ai.domain.agent.service.subagent.SubAgentToolSpecs;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.infrastructure.tool.ToolCatalogMarkdownRenderer;
import cn.lunalhx.ai.infrastructure.skill.SkillTools.ActivateSkillTool;
import cn.lunalhx.ai.infrastructure.skill.SkillTools.CopySkillResourceTool;
import cn.lunalhx.ai.infrastructure.skill.SkillTools.CreateSkillTool;
import cn.lunalhx.ai.infrastructure.skill.SkillTools.ReadSkillResourceTool;
import cn.lunalhx.ai.infrastructure.tool.CodeSearchTool;
import cn.lunalhx.ai.infrastructure.tool.DeleteFilesTool;
import cn.lunalhx.ai.infrastructure.tool.FindFilesTool;
import cn.lunalhx.ai.infrastructure.tool.GitOpTool;
import cn.lunalhx.ai.infrastructure.tool.ListDirectoryTool;
import cn.lunalhx.ai.infrastructure.tool.MemorySaveTool;
import cn.lunalhx.ai.infrastructure.tool.MemorySearchTool;
import cn.lunalhx.ai.infrastructure.tool.ReadFileTool;
import cn.lunalhx.ai.infrastructure.tool.ReplaceInFileTool;
import cn.lunalhx.ai.infrastructure.tool.RunShellTool;
import cn.lunalhx.ai.infrastructure.tool.TodoWriteTool;
import cn.lunalhx.ai.infrastructure.tool.WriteFileTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

@RunWith(SpringRunner.class)
@SpringBootTest
public class ToolCatalogDocTest {

    private static final Path GENERATED_DIR = Paths.get("../docs/generated").toAbsolutePath().normalize();
    private static final Path OUTPUT_FILE = GENERATED_DIR.resolve("function-calling-tools.md");

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AgentMemoryRepository agentMemoryRepository;

    @MockBean
    private SkillRepository skillRepository;

    @MockBean
    private ContextArtifactRepository contextArtifactRepository;

    @MockBean
    private ContextBlobStore contextBlobStore;

    @Test
    public void generatedDocMatchesCommitted() throws IOException {
        List<ToolSpec> specs = collectStaticSpecs();
        ToolCatalogMarkdownRenderer renderer = new ToolCatalogMarkdownRenderer(objectMapper);
        String generated = renderer.render(specs);

        if ("true".equalsIgnoreCase(System.getProperty("toolDocs.update"))) {
            Files.createDirectories(GENERATED_DIR);
            Files.writeString(OUTPUT_FILE, generated);
            return;
        }

        if (!Files.exists(OUTPUT_FILE)) {
            Files.createDirectories(GENERATED_DIR);
            Files.writeString(OUTPUT_FILE, generated);
            return;
        }

        String committed = Files.readString(OUTPUT_FILE).replace("\r\n", "\n");
        String expected = generated.replace("\r\n", "\n");
        if (!committed.equals(expected)) {
            Path expFile = Paths.get("../target/toolcat-expected.txt").toAbsolutePath().normalize();
            Path comFile = Paths.get("../target/toolcat-committed.txt").toAbsolutePath().normalize();
            Files.writeString(expFile, expected);
            Files.writeString(comFile, committed);
            assertEquals("Mismatch. See " + expFile + " vs " + comFile, expected, committed);
        }
    }

    private List<ToolSpec> collectStaticSpecs() {
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        List<ToolSpec> specs = new ArrayList<>();
        // Built-in tools (FileSystemToolSupport subclasses)
        specs.add(new CodeSearchTool(props).spec());
        specs.add(new DeleteFilesTool(props).spec());
        specs.add(new FindFilesTool(props).spec());
        specs.add(new GitOpTool(props).spec());
        specs.add(new ListDirectoryTool(props).spec());
        specs.add(new ReadFileTool(props).spec());
        specs.add(new ReplaceInFileTool(props).spec());
        specs.add(new RunShellTool(props).spec());
        specs.add(new WriteFileTool(props).spec());
        // Standalone tools
        specs.add(new TodoWriteTool().spec());
        specs.add(new ContextRecallTool(contextArtifactRepository, contextBlobStore).spec());
        // Memory tools (conditional)
        specs.add(new MemorySaveTool(agentMemoryRepository).spec());
        specs.add(new MemorySearchTool(agentMemoryRepository).spec());
        // Skill tools
        specs.add(new ReadSkillResourceTool(skillRepository).spec());
        specs.add(new CopySkillResourceTool(skillRepository).spec());
        specs.add(new CreateSkillTool(".agents/skills").spec());
        specs.add(new ActivateSkillTool(skillRepository).spec());
        // Sub-Agent
        specs.add(SubAgentToolSpecs.spawnAgentsSpec());
        return specs;
    }
}
