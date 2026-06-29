package cn.lunalhx.ai.infrastructure.adapter.snapshot;

import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceSnapshotPort.WorkspaceFileChange;
import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceSnapshotPort.WorkspaceFileChangeType;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class GitWorkspaceSnapshotAdapterTest {

    @Test
    public void parsesNullDelimitedNameStatusOutput() {
        String output = "M\0webpage/hello_world.html\0"
                + "A\0src/file with spaces.txt\0"
                + "D\0src/file\twith-tab.txt\0";

        List<WorkspaceFileChange> changes =
                GitWorkspaceSnapshotAdapter.parseNameStatus(output);

        assertEquals(3, changes.size());
        assertEquals("webpage/hello_world.html", changes.get(0).path());
        assertEquals(WorkspaceFileChangeType.MODIFIED, changes.get(0).changeType());
        assertEquals("src/file with spaces.txt", changes.get(1).path());
        assertEquals(WorkspaceFileChangeType.ADDED, changes.get(1).changeType());
        assertEquals("src/file\twith-tab.txt", changes.get(2).path());
        assertEquals(WorkspaceFileChangeType.DELETED, changes.get(2).changeType());
    }

    @Test
    public void ignoresIncompleteNullDelimitedRecord() {
        List<WorkspaceFileChange> changes =
                GitWorkspaceSnapshotAdapter.parseNameStatus("M\0");

        assertEquals(0, changes.size());
    }
}
